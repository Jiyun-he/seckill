# 架构与核心链路

[← 返回 README](../README.md)

## 目录

- [分层结构](#分层结构)
- [包结构](#包结构)
- [核心组件](#核心组件)
- [认证链路](#认证链路)
- [普通下单链路](#普通下单链路)
- [秒杀下单链路](#秒杀下单链路)

## 分层结构

项目为 Spring Boot 单体应用，按接口层 / 业务层 / 数据访问层 / 中间件能力分层：

```text
Client / API Tester / JMeter
        |
        v
Controller Layer
        |
        v
Service Layer
        |
        +--------------------+--------------------+
        |                    |                    |
        v                    v                    v
MySQL / MyBatis-Plus      Redis / Lua        RabbitMQ
        |                    |                    |
        v                    v                    v
Goods / SeckillGoods    Stock / Ordered    Async Order
Order / User            Activity / Preoccupy  DLQ Compensation
```

秒杀热路径的设计目标是把绝大部分并发请求拦截在 Redis 层：一次 Lua 调用即可完成活动时间校验、一人一单查重、库存扣减与预占状态登记，随后异步投递 MQ 并立即返回，MySQL 写入交由消费者完成。

## 包结构

主包路径：

```text
com.example.seckill
```

| 包名 | 作用 |
| --- | --- |
| `controller` | 对外提供 REST API |
| `service` / `service.impl` | 商品、订单、秒杀、用户等业务逻辑；`SeckillOrderConsumer` 与 `SeckillReconciliationScanner` 也位于 `service` 包 |
| `mapper` | 基于 MyBatis-Plus 访问 MySQL |
| `entity` | 数据库表映射 |
| `dto` | 接口请求参数 |
| `vo` | 接口响应视图对象 |
| `converter` | Entity 到 VO 的转换 |
| `config` | Redis、RabbitMQ、MyBatis-Plus、登录拦截器与 OpenAPI 配置 |
| `common` | 统一响应结构、全局异常处理、订单状态枚举 |
| `fault` | 故障注入埋点（Failpoint），仅在 `fault-test` profile 下生效 |
| `util` / `utils` | 雪花 ID 生成、JWT 生成与校验 |

## 核心组件

| 组件 | 类型 | 职责 |
| --- | --- | --- |
| `LoginInterceptor` | 拦截器 | 校验 JWT 与 Redis 登录态，将 `userId` 注入 request attribute |
| `SeckillController` | 控制器 | 秒杀商品详情、执行秒杀、订单状态查询 |
| `SeckillServiceImpl` | 服务 | 库存预热、活动时间校验、Lua 原子预占、消息投递与 confirm 回调补偿 |
| `SeckillOrderConsumer` | MQ 消费者 | 异步落库与扣减 MySQL 库存；死信队列补偿 |
| `SeckillReconciliationScanner` | 定时任务 | 扫描中间态预占记录，按 DB 权威重投或补偿；轻量库存对账 |
| `SnowflakeIdUtil` | 工具 | 雪花算法生成订单号 |
| `JwtUtil` | 工具 | JWT 生成、解析、校验 |
| `RabbitMqConfig` | 配置 | 交换机、队列、死信队列、重试拦截器、confirm / return 回调 |

## 认证链路

```text
注册 / 登录
     ↓
BCrypt 校验或加密密码
     ↓
生成 JWT Token
     ↓
写入 Redis，key 为 token:{userId}
     ↓
客户端后续请求携带 Authorization: Bearer <token>
     ↓
LoginInterceptor 校验 JWT 与 Redis 登录态
     ↓
解析 userId 并写入 request attribute
     ↓
业务接口基于 userId 执行下单或秒杀
```

Token 同时存在于 JWT 与 Redis 中：JWT 提供无状态的自包含声明（签名与过期时间），Redis 提供可主动失效的服务端会话记录。

## 普通下单链路

```text
提交商品 ID 与购买数量
     ↓
查询商品是否存在
     ↓
MySQL 条件更新扣减库存（stock >= quantity）
     ↓
生成订单号
     ↓
写入订单表
     ↓
返回订单信息
```

普通下单直接用数据库条件更新保证库存不会扣成负数，适合常规订单场景。

## 秒杀下单链路

秒杀链路把同步路径压缩到 Redis 与 MQ，避免高并发请求直接压向 MySQL：

```text
应用启动时预热活动时间与库存到 Redis
     ↓
用户请求 POST /seckill/do/{seckillGoodsId}
     ↓
从 Redis 读取活动时间段（热路径不访问 MySQL）
     ↓
生成雪花订单号
     ↓
单个 Lua 脚本原子完成：
  校验时间段 + 一人一单查重 + 扣减库存 + 占位 + 写入订单预占状态
     ↓
发送订单消息到 RabbitMQ
     ↓
立即返回订单号
     ↓
消费者异步落库并扣减 MySQL 秒杀库存
     ↓
事务提交后状态置为 CONSUMED
     ↓
消费失败 → 重试；重试耗尽 → 死信队列幂等补偿
     ↓
confirm 结果未知（超时）→ 状态置 RETRY，由对账 Scanner 兜底重投
```

接口返回订单号时订单尚未落库，只代表「已受理」。要确认最终结果，请轮询 [`GET /seckill/order/{orderNo}`](api.md#查询秒杀订单状态)，其状态语义见[一致性设计](consistency.md#订单状态机)。

## 延伸阅读

- [数据模型](data-model.md) —— 表结构、索引、Redis key 与 RabbitMQ 拓扑
- [一致性设计](consistency.md) —— 原子预占、状态机、补偿与对账
