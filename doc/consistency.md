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
| `RETRY` | confirm 超时或结果未知，待对账框架裁决 | confirm 回调 |
| `FAILED` | 终态：最终失败，库存已补偿或校准 | 补偿路径、对账 Scanner |
| `CONSUMED` | 终态：消费落库成功 | 消费者 `afterCommit` |

```text
        预占成功
           │
           ▼
        PENDING ──confirm ack──→ CONFIRMED ──消费落库──→ CONSUMED
           │                          │                     ▲
           │                          │                     │
    confirm 超时/结果未知              │            对账发现 DB 已有订单
           │                          │            （不补偿，仅修状态）
           ▼                          ▼                     │
         RETRY ──────────────────→ 对账裁决 ────────────────┘
                                      │
                          重投耗尽 / 明确失败
                                      │
                                      ▼
                                   FAILED
```

对外查询时，中间态会被合并展示。`SeckillOrderStatus#toUserVisible()` 把 `PENDING` / `CONFIRMED` / `RETRY` 统一映射为 `PROCESSING`，终态原样返回；查询时若 Redis 中已无记录（TTL 过期或从未写入），则以数据库订单是否存在为准，返回 `CONSUMED` 或 `NOT_FOUND`。

因此 `GET /seckill/order/{orderNo}` 只是一个**尽力而为的查询视图**，数据库始终是最终事实。

TTL 分两档：中间态 3600 秒（超时交给对账），终态 86400 秒（保证用户有查询窗口）。

## 投递可靠性

`sendSeckillOrderMessage()` 在发送时注册 `CorrelationData` 回调，按结果分四种情况处理：

| 分支 | 触发条件 | 处理 |
| --- | --- | --- |
| 消息无法路由 | `getReturned() != null` | 明确失败 → 补偿 Redis |
| confirm 超时 / 异常 | `ex != null` | 结果未知 → 状态置 `RETRY`，交对账 |
| confirm ack | `confirm.isAck()` | 投递成功 → 状态置 `CONFIRMED` |
| confirm nack | 其余 | 明确失败 → 补偿 Redis |

关键区分是「**明确失败**」与「**结果未知**」：无法路由、nack、以及 `convertAndSend` 同步抛异常都能确定消息没有成功投递，可以立即补偿；而 confirm 超时只说明回答没回来，消息可能已经投出去了，此时若贸然补偿会造成「库存还回去了但订单也落库了」的双花，所以只标记 `RETRY` 交给对账框架按数据库事实裁决。

## 补偿与校准

补偿与校准都做幂等保护：`COMPENSATE_LUA` 开头检查 `status`，若已是 `FAILED` 或 `CONSUMED` 直接返回 0，不重复执行。

两类失败的处理方式**不同**：

| 失败类型 | 触发场景 | 处理方式 |
| --- | --- | --- |
| 系统失败 | MQ 投递失败、confirm nack、无法路由、重试耗尽进入死信 | Redis 库存 `+1`、`SREM` 移除占位、状态置 `FAILED` |
| 业务失败 | DB 库存已耗尽，说明 Redis 与 DB 已漂移 | **不 `+1`**，将 Redis 库存校准为 DB 真实值，状态置 `FAILED` |

业务失败之所以不能简单 `+1`，是因为此时的偏差来自 Redis 与 DB 的库存不一致，`+1` 只会让 Redis 继续偏离真相、制造出根本不存在的库存。消费者在 DB 条件扣减失败时调用 `calibrateRedisStock()`，直接以 DB 当前值覆盖 Redis，并抛出 `AmqpRejectAndDontRequeueException` 让事务回滚、消息转入死信路径。

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
       └─ retryCount = 3 → COMPENSATE_LUA 幂等补偿 → FAILED
```

先查库再决定是否补偿，是为了堵住这个窗口：消费者可能已经提交了事务，但在把状态写回 `CONSUMED` 之前崩溃。此时状态停留在中间态，而订单其实已经落库 —— 若不查库就补偿，会把一个成功订单的库存错误地还回去。

多实例部署时通过 `seckill:reconcile:lock:{orderNo}`（`SETNX`，TTL 30 秒）保证同一订单不会被两个实例同时处理。

### 库存对账

每 5 分钟做一次轻量库存对账，期望值为：

```text
期望 Redis 库存 = DB 库存 − 活跃预占数
```

活跃预占数即当前处于中间态的订单数量（Redis 已扣、DB 未扣）。当 Redis 库存**高于**期望值（超卖侧）时按期望值校准；低于期望值不做处理，避免在对账任务中盲目增加库存。

参数见 `SeckillReconciliationScanner`（`TIMEOUT_MS = 120_000`、`MAX_RETRY = 3`、`LOCK_TTL_SECONDS = 30`、两个 `@Scheduled` 的 `fixedDelay`）。

## 终态权威原则

状态机的一个隐蔽缺陷是**晚到的 MQ 回调可能覆盖终态**：如果消费落库（`CONSUMED`）先完成，而 confirm ack 或超时回调后到，无条件 `HSET status` 会把终态回退成 `CONFIRMED` / `RETRY`，导致状态查询短暂显示错误。

修复方式是让所有中间态转移走同一个原子 CAS 脚本 `UPDATE_STATUS_LUA`，与补偿脚本的终态检查对齐：

```lua
local status = redis.call('hget', KEYS[1], 'status')
if status == 'FAILED' or status == 'CONSUMED' then
  return 0
end
redis.call('hset', KEYS[1], 'status', ARGV[1], 'updatedAt', ARGV[2])
redis.call('expire', KEYS[1], ARGV[3])
return 1
```

即 **`FAILED` / `CONSUMED` 具有更高权威，不可被覆盖**。消费者侧同样有对称的检查：`handleSeckillOrder()` 开头若发现状态已是终态，直接幂等返回（ACK），不复活已补偿或已成功的交易。

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
