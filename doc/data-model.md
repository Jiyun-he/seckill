# 数据模型

[← 返回 README](../README.md)

本文描述 MySQL 表结构、Redis 键空间与 RabbitMQ 拓扑。初始化脚本见 [`docker/mysql/init/01-init.sql`](../docker/mysql/init/01-init.sql)。

## 目录

- [MySQL 表结构](#mysql-表结构)
- [Redis 键空间](#redis-键空间)
- [RabbitMQ 拓扑](#rabbitmq-拓扑)

## MySQL 表结构

涉及四张表：

```text
user            用户
goods           普通商品
seckill_goods   秒杀商品（活动）
order           订单
```

### `user`

| 列名 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK AUTO_INCREMENT | 用户 ID |
| `username` | VARCHAR(50) NOT NULL | 用户名 |
| `password` | VARCHAR(100) NOT NULL | BCrypt 加密后的密码 |
| `phone` | VARCHAR(20) | 手机号 |
| `create_time` | DATETIME | 创建时间 |

索引：

```sql
UNIQUE KEY uk_username (username)
```

### `goods`

| 列名 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK AUTO_INCREMENT | 商品 ID |
| `name` | VARCHAR(200) NOT NULL | 商品名称 |
| `price` | DECIMAL(10,2) NOT NULL | 商品价格 |
| `stock` | INT NOT NULL | 普通库存 |
| `detail` | TEXT | 商品详情 |
| `create_time` | DATETIME | 创建时间 |

普通下单直接基于该表做条件扣减。

### `seckill_goods`

| 列名 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK AUTO_INCREMENT | 秒杀商品 ID |
| `goods_id` | BIGINT NOT NULL | 关联 `goods.id` |
| `seckill_price` | DECIMAL(10,2) NOT NULL | 秒杀价 |
| `seckill_stock` | INT NOT NULL | 秒杀库存 |
| `start_time` | DATETIME NOT NULL | 活动开始时间 |
| `end_time` | DATETIME NOT NULL | 活动结束时间 |

索引：

```sql
KEY idx_goods_id (goods_id)
KEY idx_start_time (start_time)
```

`start_time` 除标记活动起点外，还充当**活动版本标识**，参与拼接 Redis 库存 key 与一人一单集合 key，使不同场次的库存互相隔离。因此活动开始后 `seckill_price` / `start_time` / `end_time` 视为冻结配置，详见[一致性设计 § 交易配置冻结](consistency.md#交易配置冻结)。

### `order`

| 列名 | 类型 | 说明 |
| --- | --- | --- |
| `order_no` | BIGINT PK | 订单号，雪花算法生成 |
| `user_id` | BIGINT NOT NULL | 用户 ID |
| `goods_id` | BIGINT NOT NULL | 商品 ID |
| `seckill_goods_id` | BIGINT | 秒杀商品 ID；普通订单为 NULL |
| `goods_name` | VARCHAR(200) | 商品名称快照 |
| `goods_price` | DECIMAL(10,2) NOT NULL | 成交单价快照 |
| `quantity` | INT NOT NULL | 数量 |
| `total_amount` | DECIMAL(10,2) NOT NULL | 总金额 |
| `status` | TINYINT NOT NULL DEFAULT 0 | 订单状态，当前恒为 0（未支付） |
| `create_time` | DATETIME | 创建时间 |

索引：

```sql
PRIMARY KEY (order_no)
UNIQUE KEY uk_user_seckill (user_id, seckill_goods_id)
KEY idx_user_id (user_id)
```

`order_no` 直接作为主键，没有自增 `id` 列。`uk_user_seckill` 是「一人一单」的最终兜底：即使 Redis 层查重被绕过，同一用户对同一秒杀商品也只能留下一行记录。`goods_name` 与 `goods_price` 为下单时刻的快照，避免商品改价影响历史订单。

## Redis 键空间

| Key | 类型 | 内容 | TTL |
| --- | --- | --- | --- |
| `seckill:stock:{id}:{startTime}` | String | 秒杀剩余库存 | 无 |
| `seckill:activity:{id}` | String | 活动时间段，格式 `startTimeStr\|startMillis\|endMillis` | 无 |
| `seckill:ordered:{id}:{startTime}` | Set | 已参与该场次的 userId | 无 |
| `seckill:order:{orderNo}` | Hash | 订单预占状态 | 见下 |
| `seckill:goods:{id}` | String (JSON) | 秒杀商品详情缓存 | 300~600s 随机 |
| `seckill:reconcile:lock:{orderNo}` | String | 对账任务的抢占锁 | 30s |
| `token:{userId}` | String | 登录态 | 跟随 JWT 过期时间 |

其中 `{startTime}` 的格式为 `yyyyMMddHHmmss`（见 `SeckillServiceImpl#buildStockKey`）。

`seckill:order:{orderNo}` 为 Hash，字段如下：

| 字段 | 说明 |
| --- | --- |
| `status` | 订单状态，取值见[订单状态机](consistency.md#订单状态机) |
| `userId` | 下单用户 |
| `seckillGoodsId` | 秒杀商品 ID |
| `startTime` | 活动版本，用于还原带版本的 key |
| `updatedAt` | 最近状态变更时间戳，对账超时判定依据 |
| `retryCount` | 对账重投次数 |

Hash 的 TTL 按状态分两档：中间态（PENDING / CONFIRMED / RETRY）保留 3600 秒，超时由对账框架接管；终态（FAILED / CONSUMED）保留 86400 秒，保证用户有足够的查询窗口。常量定义见 `SeckillOrderStatus#INTERMEDIATE_TTL_SECONDS` 与 `#FINAL_TTL_SECONDS`。

`seckill:activity:{id}` 让秒杀入口在 Lua 内完成时间校验，热路径完全不需要访问 MySQL。

## RabbitMQ 拓扑

```text
                    seckill.exchange (Direct)
                            │
                 routingKey: seckill.order
                            │
                            ▼
                   ┌──────────────────┐
                   │   seckill.queue   │  durable
                   │   DLX → seckill.dlx
                   └────────┬─────────┘
                            │
                   handleSeckillOrder()
                   落库 + 扣减 DB 库存
                            │
                  消费异常 → 重试 3 次（1s / 2s / 4s）
                            │
                     重试耗尽 → 拒绝进入 DLX
                            │
                            ▼
                    seckill.dlx (Direct)
                            │
                 routingKey: seckill.order
                            │
                            ▼
                 ┌────────────────────┐
                 │  seckill.queue.dlq  │  durable
                 └─────────┬──────────┘
                           │
                  handleDeadLetter()
                  幂等补偿 Redis 库存
```

| 配置项 | 值 | 定义位置 |
| --- | --- | --- |
| 交换机 | `seckill.exchange`（Direct，durable） | `RabbitMqConfig#SECKILL_EXCHANGE` |
| 队列 | `seckill.queue`（durable，绑定 DLX） | `RabbitMqConfig#SECKILL_QUEUE` |
| 路由键 | `seckill.order` | `RabbitMqConfig#SECKILL_ROUTING_KEY` |
| 死信交换机 | `seckill.dlx`（Direct，durable） | `RabbitMqConfig#SECKILL_DLX` |
| 死信队列 | `seckill.queue.dlq`（durable） | `RabbitMqConfig#SECKILL_DLQ` |
| 消费端重试 | 最多 3 次，退避 1s / 2s / 4s | `RabbitMqConfig#retryInterceptor` |
| 重试耗尽策略 | `RejectAndDontRequeueRecoverer` → 进入 DLQ | 同上 |
| 消费并发 | 默认 2，由 `SEKKILL_CONSUMER_CONCURRENCY` 控制 | `RabbitMqConfig#rabbitListenerContainerFactory` |
| 生产者确认 | `publisher-confirm-type: correlated`，`publisher-returns: true`，`mandatory: true` | `application.yml` |

消息体为 JSON，包含 `userId`、`seckillGoodsId`、`orderNo`、`startTime` 四个字段。其中 `startTime` 是活动版本标识，让消费者与死信消费者能够还原出带版本的库存 key。
