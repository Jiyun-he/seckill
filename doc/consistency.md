# 一致性设计

[← 返回 README](../README.md)

秒杀的核心矛盾是：接口必须快（不能同步写库），但订单又必须准（不能超卖、不能重复、不能丢）。本文说明本项目如何在 Redis、RabbitMQ、MySQL 三层之间维持这个平衡。

## 目录

- [Lua 原子预占](#lua-原子预占)
- [一人一单](#一人一单)
- [订单状态机](#订单状态机)
- [投递可靠性](#投递可靠性)
- [补偿与校准](#补偿与校准)
- [异常交易对账](#异常交易对账)
- [终态权威原则](#终态权威原则)
- [缓存防护](#缓存防护)
- [交易配置冻结](#交易配置冻结)

## Lua 原子预占

秒杀入口的整个资格判定与库存扣减在**一个 Lua 脚本**内完成，借助 Redis 单线程执行的特性获得天然原子性，不需要分布式锁：

```text
SECKILL_LUA
  KEYS[1] = seckill:stock:{id}:{startTime}     库存
  KEYS[2] = seckill:ordered:{id}:{startTime}   已参与用户
  KEYS[3] = seckill:order:{orderNo}            预占状态 Hash
  ARGV    = userId, orderNo, now, startMillis, endMillis, seckillGoodsId, startTime

  1. now < start 或 now > end        → return -5
  2. SISMEMBER 已参与                → return -4
  3. GET 库存为 nil                  → return -2
  4. 库存非数字                      → return -3
  5. 库存 <= 0                       → return -1
  6. DECR 库存
  7. SADD 已参与集合
  8. HSET 预占状态（status=PENDING，TTL 3600s）
  9. return 扣减后的库存
```

返回值语义：

| 返回码 | 含义 | 接口响应 |
| --- | --- | --- |
| `>= 0` | 扣减成功，返回剩余库存 | 200，返回订单号 |
| `-1` | 库存不足 | 409 |
| `-2` | 库存未初始化 | 500 |
| `-3` | 库存数据异常（非数字） | 500 |
| `-4` | 已参与过该秒杀 | 409 |
| `-5` | 不在秒杀时间段内 | 403 |
| `null` | 脚本执行异常 | 429 |

把「查重」与「占位」放进同一个脚本，是这里最关键的一点：如果先 `SISMEMBER` 判定、再由 Java 侧 `SADD`，两步之间存在窗口，同一用户并发请求可以同时通过查重。合并进 Lua 后这个窗口不复存在。

活动时间在脚本内校验而非查库，是因为 `seckill:activity:{id}` 已在启动预热时写入 Redis，热路径因此完全不触碰 MySQL。

常量定义见 `SeckillServiceImpl`（`RETURN_*` 静态字段与 `SECKILL_LUA` 脚本体）。

## 一人一单

三层限制，逐层收紧：

1. **Lua 内原子查重**：`SISMEMBER` + `SADD` 与库存扣减在同一脚本内完成，并发请求无法同时通过。
2. **Redis Set 记录参与用户**：`seckill:ordered:{id}:{startTime}` 保存已参与该场次的 userId。带上活动版本是为了让上一场次的记录不污染下一场。
3. **数据库唯一索引兜底**：`order` 表的 `uk_user_seckill (user_id, seckill_goods_id)` 是最终防线，即使前两层因异常（如 Redis 数据被误清）失效，重复插入也会被数据库拒绝。

## 订单状态机

Redis 中 `seckill:order:{orderNo}` 的 `status` 字段是**投递状态**，与订单本身的支付状态无关：

| 状态 | 含义 | 写入方 |
| --- | --- | --- |
| `PENDING` | 预占成功，消息发送中（confirm 前） | `SECKILL_LUA` |
| `CONFIRMED` | confirm ack，消息已到达 Broker，等待消费落库 | confirm 回调 |
| `FAILED` | 终态：最终失败，库存已补偿或校准 | 补偿路径、对账 Scanner |
| `CONSUMED` | 终态：消费落库成功 | 消费者 `afterCommit` |

```text
        预占成功
           │
           ▼
        PENDING ──confirm ack──→ CONFIRMED          ┐ 中间态
           │                        │                │ 悬挂 120s 由对账接管
           └───────────┬────────────┘                ┘
                       │
                       ├──── 消费落库成功 ────→ CONSUMED（终态）
                       │
                   悬挂超时（120s 无更新）
                       │
                       ▼
                   对账裁决
                       │
           ┌───────────┴───────────┐
           │                       │
      DB 已有订单              DB 无订单
           │                       │
           ▼                 重投（≤3 次）
      修正为 CONSUMED              │
                         重试耗尽 / 消息退回
                                   ▼
                               FAILED（终态）
```

对外查询时，中间态会被合并展示。`SeckillOrderStatus#toUserVisible()` 把 `PENDING` / `CONFIRMED` 统一映射为 `PROCESSING`，终态原样返回；查询时若 Redis 中已无记录（TTL 过期或从未写入），则以数据库订单是否存在为准，返回 `CONSUMED` 或 `NOT_FOUND`。

因此 `GET /seckill/order/{orderNo}` 只是一个**尽力而为的查询视图**，数据库始终是最终事实。

TTL 分两档：中间态 3600 秒（超时交给对账），终态 86400 秒（保证用户有查询窗口）。

## 投递可靠性

`sendSeckillOrderMessage()` 在发送时注册 `CorrelationData` 回调，按结果分三种情况处理：

| 分支 | 触发条件 | 处理 |
| --- | --- | --- |
| 消息无法路由 | `getReturned() != null` | 确定未入队 → 提交加锁补偿裁决 |
| confirm ack | `ex == null && confirm.isAck()` | 投递成功 → 状态置 `CONFIRMED` |
| 其余（nack / future 异常完成） | `else` | 结果未知 → 不做断言，状态保持 `PENDING` 交对账 |

判据只有一条：**能否证明消息没有发出去**。

- **能证明**：`convertAndSend` 同步抛异常（消息根本没发出）、消息被退回（`getReturned() != null`，broker 收到但无处可投，未入队）。这两种情况可以立即触发裁决，但仍统一经过商品行锁与数据库事实核对，不绕过并发协议直接执行 Lua。
- **不能证明**：confirm nack 与 future 异常完成。二者都只说明「没拿到可靠回音」——Spring AMQP 在连接断开时会为所有未确认消息补发 nack（`PublisherCallbackChannelImpl#generateNacksForPendingAcks`），此时消息可能**早已入队甚至已被消费**；而 `CorrelationData#getFuture()` 在框架内只有正常完成路径（`complete(Confirm)`），不存在异常完成。贸然补偿会造成「库存还回去了但订单也落库了」的双花，因此一律不做断言，交由对账按数据库事实裁决。

代价是 broker 真 nack（队列满、内部错误）时反馈变慢：由「立即 `FAILED`」变为「120 秒超时 + 3 次重投后 `FAILED`」，约 8 分钟。相对于误判导致用户看到 24 小时的错误状态，这个取舍是有意的。

## 补偿与校准

补偿与校准都做严格幂等保护：Lua 只允许 `PENDING` / `CONFIRMED` 进入 `FAILED`；已是 `FAILED` 返回幂等成功，遇到 `CONSUMED` 返回终态冲突，状态缺失或非法则拒绝修改。库存恢复、释放用户占位和写入 `FAILED` 在同一个 Lua 脚本中完成。

两类失败的处理方式**不同**：

| 失败类型 | 触发场景 | 处理方式 |
| --- | --- | --- |
| 系统失败 | 明确投递失败、无法路由、DLQ、对账重试耗尽 | 经加锁裁决确认 DB 无订单后，Redis 库存 `+1`、`SREM` 移除占位、状态置 `FAILED` |
| 业务失败 | DB 库存已耗尽，说明 Redis 与 DB 已漂移 | **不 `+1`**，将 Redis 库存校准为 DB 真实值，状态置 `FAILED` |

业务失败之所以不能简单 `+1`，是因为此时的偏差来自 Redis 与 DB 的库存不一致，`+1` 只会让 Redis 继续偏离真相、制造出根本不存在的库存。消费者在 DB 条件扣减失败时调用 `calibrateRedisStock()`，直接以 DB 当前值覆盖 Redis，并抛出 `AmqpRejectAndDontRequeueException` 让事务回滚、消息转入死信路径。

### 消费与最终补偿的并发协议

所有可能把订单终止为 `FAILED` 的入口（明确投递失败、DLQ、Scanner 重试耗尽）都调用 `SeckillCompensationService`。它与消费者遵守相同顺序：

```text
锁定 seckill_goods(id) → 按 order_no 当前读订单 → 读取 Redis 状态 → 落库或终止裁决
```

- 消费者持锁后若 DB 已有订单则幂等结束；若 DB 无订单，只允许 `PENDING` / `CONFIRMED` 继续落库，`FAILED` 必须终止，`CONSUMED` 或状态缺失视为一致性异常。
- 补偿方持锁后重新查库：DB 有订单时禁止归还库存并尝试将中间态收敛为 `CONSUMED`；DB 无订单且状态仍为中间态时才执行补偿 Lua。
- `DB 有订单 + Redis FAILED` 与 `DB 无订单 + Redis CONSUMED` 都是需要告警/人工对账的冲突，普通流程不会用另一终态覆盖它。

商品行锁覆盖消费者 SQL 事务直至提交，因此补偿不可能在“查无订单”之后越过一个已在途的同商品消费事务；反过来，补偿先完成并提交 `FAILED` 后，晚到消费者会在持锁二次检查时拒绝落库。代价是同一秒杀商品的数据库消费被串行化，吞吐影响必须用相同压测口径验证。`order_no` 是主键，锁定查询走唯一索引；所有路径固定使用商品行再订单行的顺序，降低交叉等待风险。

## 异常交易对账

`SeckillReconciliationScanner` 是「结果未知」类失败的统一出口。

### 中间态扫描

每 30 秒扫描 `seckill:order:*`，对超过 120 秒未更新的中间态记录，**以 MySQL 订单为最终事实**收敛：

```text
扫描中间态超时记录
  │
  ├─ DB 已有订单 → 修正为 CONSUMED    （禁止补偿，订单已存在）
  │
  └─ DB 无订单
       ├─ retryCount < 3 → retryCount+1，重投消息
       └─ retryCount = 3 → 加锁补偿裁决 → Lua 幂等补偿 → FAILED
```

Scanner 的第一次查库只用于快速分流，不能单独证明可以补偿。最终终止前仍必须进入共享商品行锁，并在锁内再次查询订单；这是为了同时覆盖“事务已提交但状态未写回”和“第一次查询时消费事务尚未提交”两个窗口。

多实例部署时通过 `seckill:reconcile:lock:{orderNo}`（`SETNX`，TTL 30 秒）保证同一订单不会被两个实例同时处理。

### 库存对账

每 5 分钟做一次轻量库存对账，期望值为：

```text
期望 Redis 库存 = DB 库存 − 活跃预占数
```

活跃预占数即当前处于中间态的订单数量（Redis 已扣、DB 未扣）。当 Redis 库存**高于**期望值（超卖侧）时按期望值校准；低于期望值不做处理，避免在对账任务中盲目增加库存。

参数见 `SeckillReconciliationScanner`（`TIMEOUT_MS = 120_000`、`MAX_RETRY = 3`、`LOCK_TTL_SECONDS = 30`、两个 `@Scheduled` 的 `fixedDelay`）。

## 终态权威原则

状态机的一个隐蔽缺陷是**晚到的 MQ 回调可能覆盖终态**：如果消费落库（`CONSUMED`）先完成，而 confirm ack 回调后到，无条件 `HSET status` 会把终态回退成 `CONFIRMED`，导致状态查询短暂显示错误。

修复方式是由 `SeckillOrderStateStore` 为每种转换提供严格 Lua CAS：

```text
PENDING ──confirm──> CONFIRMED
PENDING / CONFIRMED ──SQL committed──> CONSUMED
PENDING / CONFIRMED ──compensate──> FAILED

FAILED 与 CONSUMED 不允许互相覆盖；重复写入相同终态幂等返回。
```

消费者的 `afterCommit` 也走 `markConsumed()` CAS，不能再无条件覆盖 `FAILED`。消费者在持有商品行锁后同时核对 DB 与 Redis：只有 DB 已存在订单时才能把中间态收敛为 `CONSUMED`；DB 不存在却看到 `CONSUMED` 时会报告一致性异常，而不是把它当作正常幂等成功。

这套协议仍有明确边界：它依赖订单 Redis 记录的终态 TTL（当前 24 小时）阻止任意晚到消息。超过 TTL 后状态缺失，消费者会拒绝落库并告警，但系统不声称能仅凭 Redis 自动修复任意时间后的跨系统异常。

## 缓存防护

- **缓存穿透**：查询不存在的秒杀商品时，写入短期空对象缓存，避免无效请求持续穿透到 MySQL。
- **缓存雪崩**：正常商品缓存设置 300~600 秒的**随机** TTL，避免大批 key 同时失效。
- **商品详情缓存与实时库存分离读取**：`seckill:goods:{id}` 缓存商品静态信息，库存值每次从 `seckill:stock:{id}:{startTime}` 实时读取后覆盖，因此缓存命中也不会返回过期库存。

## 交易配置冻结

活动开始后，`seckill_goods` 表的 `seckill_price`、`start_time`、`end_time` 视为**不可变配置**，运行期间不得修改。原因有两点：

1. 异步落库时消费者读取的是活动当前价格，若活动期间改价，用户下单时看到的价格与最终生成订单的价格会不一致。
2. `start_time` 参与拼接 Redis 库存与一人一单 key（活动版本标识），修改它会让已预占的库存与占位记录全部失联。

当前版本不提供运行中活动的修改接口，调整秒杀价格或活动时间应在活动开始前完成。

## 延伸阅读

- [数据模型](data-model.md) —— 表结构、Redis key 与 RabbitMQ 拓扑
- [测试](testing.md) —— 上述每条机制对应的回归用例
