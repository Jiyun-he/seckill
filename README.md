# High Concurrency Seckill

`High_concurrency_seckill` 是一个基于 Spring Boot 构建的高并发秒杀系统后端项目。项目围绕用户认证、商品查询、秒杀商品缓存、Redis 库存预热、Lua 原子化秒杀、RabbitMQ 异步下单、MySQL 最终落库、死信队列补偿和 Docker 化部署，构建了一条较完整的电商秒杀业务链路。

本项目面向电商大促、限时抢购、热点商品秒杀等典型高并发场景，重点解决瞬时流量冲击下的库存超卖、重复下单、数据库连接池被打满、缓存穿透、消息投递失败和订单最终一致性等问题。系统可以作为高并发秒杀业务、分布式缓存、消息队列削峰、异步下单和后端工程化部署能力的学习与实践项目。

## 功能特性

- 用户注册与登录
- JWT Token 认证
- Redis 保存登录态
- 登录拦截器统一鉴权
- 商品列表分页查询
- 商品名称关键字模糊搜索
- 商品详情查询
- 普通商品下单
- 普通商品库存条件扣减
- 秒杀商品详情查询
- 秒杀商品缓存到 Redis
- 秒杀商品不存在时写入短期空缓存，缓解缓存穿透
- 秒杀商品缓存随机过期，降低缓存雪崩风险
- 项目启动时将秒杀库存预热到 Redis
- Redis Lua 脚本原子化秒杀：校验活动时间段、一人一单查重、扣减库存、占位
- Redis Set 记录已参与用户，配合 Lua 原子查重实现一人一单控制
- Redis 建立订单预占状态（Hash：status/userId/seckillGoodsId/startTime/updatedAt/retryCount）
- 秒杀订单状态机：PENDING → CONFIRMED → CONSUMED / RETRY / FAILED
- 订单状态查询接口（GET /seckill/order/{orderNo}）
- 异常交易对账 Scanner：扫描悬挂中间态，按 DB 权威重投/补偿 + 库存对账
- RabbitMQ 异步创建秒杀订单，实现流量削峰
- RabbitMQ publisher confirm 与 return callback
- 消息投递幂等补偿 + 业务失败库存校准
- RabbitMQ 消费端重试与死信队列
- 消费端订单幂等性校验（终态检查 + DB 唯一键兜底）
- MySQL 唯一索引兜底防止重复秒杀订单
- MyBatis-Plus 分页与条件更新
- 统一响应格式
- 全局异常处理
- Knife4j / OpenAPI 接口文档
- Docker Compose 一键启动 MySQL、Redis、RabbitMQ 和后端服务
- 故障注入测试框架（fault-test profile + failpoint 埋点，覆盖 B1-B7 失败窗口）

## 技术栈

| 模块     | 技术                        |
| -------- | --------------------------- |
| 后端框架 | Spring Boot 3.5.3           |
| Web 框架 | Spring Web MVC              |
| ORM 框架 | MyBatis-Plus 3.5.15         |
| 数据库   | MySQL 8.0                   |
| 缓存     | Redis 6                     |
| 消息队列 | RabbitMQ                    |
| 用户认证 | JWT + Redis                 |
| 密码加密 | BCrypt                      |
| 接口文档 | Knife4j / springdoc-openapi |
| 参数校验 | Jakarta Validation          |
| 容器化   | Docker / Docker Compose     |
| 构建工具 | Maven                       |
| 开发语言 | Java 17                     |

## 系统架构

项目采用 Spring Boot 分层架构，将接口层、业务层、数据访问层和中间件能力进行拆分。

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
Goods / SeckillGoods    Stock / Token      Async Order
Order / User            Cache / OrderedSet  DLQ Compensation
                        Preoccupy Status
```

主包路径为：

```text
com.example.high_concurrency_seckill
```

主要包结构如下：

| 包名                       | 作用                                                     |
| -------------------------- | -------------------------------------------------------- |
| `controller`               | 对外提供 REST API                                        |
| `service` / `service.impl` | 实现商品、订单、秒杀、用户等核心业务逻辑                 |
| `mapper`                   | 通过 MyBatis-Plus 访问 MySQL                             |
| `entity`                   | 映射数据库表结构                                         |
| `dto`                      | 封装接口请求参数                                         |
| `vo`                       | 封装接口响应视图对象                                     |
| `converter`                | 完成 Entity 到 VO 的转换                                 |
| `config`                   | 配置 Redis、RabbitMQ、MyBatis-Plus、登录拦截器和 OpenAPI |
| `common`                   | 提供统一响应结构和全局异常处理                           |
| `utils`                    | 提供 JWT 生成与校验工具                                  |

## 核心流程

### 用户认证流程

```text
用户注册 / 登录
        ↓
BCrypt 校验或加密密码
        ↓
生成 JWT Token
        ↓
将 token 写入 Redis，key 为 token:{userId}
        ↓
客户端后续请求携带 Authorization: Bearer <token>
        ↓
LoginInterceptor 校验 JWT 与 Redis 登录态
        ↓
解析 userId 并写入 request attribute
        ↓
业务接口基于 userId 执行下单或秒杀逻辑
```

### 普通下单流程

```text
用户提交商品 ID 和购买数量
        ↓
查询商品是否存在
        ↓
判断库存是否充足
        ↓
MySQL 条件更新扣减库存：stock >= quantity
        ↓
生成订单号
        ↓
写入订单表
        ↓
返回订单信息
```

普通下单主要依赖数据库条件更新避免库存扣减为负，适合常规订单场景。秒杀场景下，为了避免高并发请求直接打到数据库，系统采用 Redis 预扣库存 + RabbitMQ 异步下单的链路。

### 秒杀下单流程

```text
项目启动时预热秒杀库存与活动时间段到 Redis
        ↓
用户请求秒杀接口
        ↓
从 Redis 读取活动时间段（不访问 MySQL）
        ↓
生成秒杀订单号（雪花算法）
        ↓
执行单个 Lua 脚本原子完成：校验时间段 + 一人一单查重 + 扣减库存 + 占位 + 建立订单预占状态
        ↓
发送秒杀订单消息到 RabbitMQ
        ↓
接口快速返回订单号
        ↓
RabbitMQ 消费端异步创建订单并扣减 MySQL 秒杀库存
        ↓
消费成功并提交事务后，订单状态置为 CONSUMED
        ↓
如果消费失败，消息重试；重试耗尽后进入死信队列并执行幂等补偿
        ↓
confirm 结果未知（超时）→ 状态置为 RETRY，由对账 Scanner 兜底重投
```

该流程将秒杀接口的关键路径控制在 Redis 和 MQ 层，避免所有请求同步访问 MySQL，从而降低数据库连接池饱和风险。

## 秒杀一致性设计

### Redis Lua 原子化秒杀

秒杀库存以 `seckill:stock:{seckillGoodsId}:{startTime}` 的形式存储在 Redis 中（`startTime` 作为活动版本标识），活动时间段预热到 `seckill:activity:{seckillGoodsId}`。秒杀请求进入后，通过单个 Lua 脚本原子完成「校验时间段 + 一人一单查重 + 扣减库存 + 占位 + 建立订单预占状态」，保证整个关键路径的原子性。

脚本返回值含义：

| 返回值 | 含义                       |
| ------ | -------------------------- |
| `>= 0` | 扣减成功，返回扣减后的库存 |
| `-1`   | 库存不足                   |
| `-2`   | 库存未初始化               |
| `-3`   | 库存数据异常               |
| `-4`   | 已参与过该秒杀             |
| `-5`   | 不在秒杀时间段内           |

### 一人一单控制

系统使用两层机制限制重复秒杀：

1. 秒杀入口的单个 Lua 脚本内先 `SISMEMBER` 查重、后 `SADD` 占位，查重与占位在同一原子操作内完成，避免同一用户重复下单。
2. Redis Set `seckill:ordered:{seckillGoodsId}:{startTime}` 记录已参与用户。

同时，数据库 `order` 表中设置了唯一索引：

```sql
UNIQUE KEY uk_user_seckill (user_id, seckill_goods_id)
```

该索引用于作为最终兜底，防止极端情况下出现重复秒杀订单。

### RabbitMQ 异步削峰

秒杀接口成功完成 Redis 预扣库存后，不直接同步创建数据库订单，而是向 RabbitMQ 发送订单消息。消费端从队列中取出消息后再执行 MySQL 写入和秒杀库存扣减。

RabbitMQ 相关配置包括：

| 配置项              | 说明                    |
| ------------------- | ----------------------- |
| `seckill.exchange`  | 秒杀订单交换机          |
| `seckill.queue`     | 秒杀订单队列            |
| `seckill.order`     | 秒杀订单 routing key    |
| `seckill.dlx`       | 死信交换机              |
| `seckill.queue.dlq` | 死信队列                |
| publisher confirm   | 确认消息是否到达交换机  |
| returns callback    | 处理消息不可路由场景    |
| consumer retry      | 消费失败后最多重试 3 次 |
| dead letter queue   | 重试耗尽后进入死信队列  |

### 订单状态机

预占状态 `seckill:order:{orderNo}` 为 Hash，字段 `status` 取值：

| 状态 | 含义 |
|------|------|
| PENDING | 预占成功，消息发送中 |
| CONFIRMED | confirm ack，消息已投递 Broker，等待消费落库 |
| RETRY | confirm 超时/结果未知，待对账重投 |
| FAILED | 最终失败（补偿完成） |
| CONSUMED | 消费落库成功 |

### Redis 补偿与校准机制

补偿与校准都幂等（终态 FAILED/CONSUMED 检查跳过），区分两种失败：

- **系统失败**（MQ 投递失败、confirm nack、死信）：`Redis 库存 +1` + 移除占位 + 置 FAILED。
- **业务失败**（DB 库存耗尽，Redis/DB 漂移）：`Redis 库存校准为 DB 真实值`（而非 +1），避免制造假库存。

### 异常交易对账 Scanner

`SeckillReconciliationScanner` 定时扫描中间态（PENDING/CONFIRMED/RETRY）超时记录，以 MySQL 订单为最终事实：

```text
扫描中间态超时
  ├─ DB 有订单 → 修正为 CONSUMED（禁止补偿）
  └─ DB 无订单 → retryCount < 3 ? 重投（retryCount+1） : 幂等补偿 → FAILED
```

另做轻量库存对账：期望 `Redis stock = DB stock - active reservations`，Redis 超出期望值（超卖侧）时按 DB 校准。

### 交易配置冻结

秒杀活动开始后，`seckill_goods` 表中的秒杀价格（`seckill_price`）与活动时间（`start_time`、`end_time`）视为不可变配置，运行期间不得修改，以保证订单异步落库时读取的价格与用户下单时看到的价格一致。

当前版本不提供运行中活动的修改接口，如需调整秒杀价格或活动时间，应在活动开始前完成。

## 数据模型

当前系统使用以下核心数据表：

```text
user
goods
seckill_goods
order
```

### `user`

用于保存用户信息，主要字段包括：

```text
id
username
password
phone
create_time
```

其中，`username` 设置唯一索引，`password` 使用 BCrypt 加密后存储。

### `goods`

用于保存普通商品信息，主要字段包括：

```text
id
name
price
stock
detail
create_time
```

普通下单时会直接基于该表进行库存条件扣减。

### `seckill_goods`

用于保存秒杀商品信息，主要字段包括：

```text
id
goods_id
seckill_price
seckill_stock
start_time
end_time
```

该表通过 `goods_id` 关联普通商品，并维护秒杀价、秒杀库存和活动时间窗口。

### `order`

用于保存订单信息，主要字段包括：

```text
id
order_no
user_id
goods_id
seckill_goods_id
goods_name
goods_price
quantity
total_amount
status
create_time
```

其中：

```sql
UNIQUE KEY uk_order_no (order_no)
UNIQUE KEY uk_user_seckill (user_id, seckill_goods_id)
```

分别用于保证订单号唯一，以及同一用户对同一秒杀商品只能形成一条秒杀订单。

## API 概览

### 健康检查与测试接口

| Method | Path       | Description                |
| ------ | ---------- | -------------------------- |
| GET    | `/hello`   | 测试服务是否启动           |
| GET    | `/test/db` | 测试数据库连接与用户表查询 |

### 用户接口

| Method | Path             | Description              | 是否需要 Token |
| ------ | ---------------- | ------------------------ | -------------- |
| POST   | `/user/register` | 用户注册，返回 JWT Token | 否             |
| POST   | `/user/login`    | 用户登录，返回 JWT Token | 否             |

### 商品接口

| Method | Path          | Description                             | 是否需要 Token |
| ------ | ------------- | --------------------------------------- | -------------- |
| GET    | `/goods/list` | 分页查询商品列表，支持 keyword 模糊搜索 | 是             |
| GET    | `/goods/{id}` | 查询商品详情                            | 是             |

### 普通订单接口

| Method | Path            | Description      | 是否需要 Token |
| ------ | --------------- | ---------------- | -------------- |
| POST   | `/order/create` | 创建普通商品订单 | 是             |

### 秒杀接口

| Method | Path                           | Description                     | 是否需要 Token |
| ------ | ------------------------------ | ------------------------------- | -------------- |
| GET    | `/seckill/goods/{id}`          | 查询秒杀商品详情，带 Redis 缓存 | 是             |
| POST   | `/seckill/do/{seckillGoodsId}` | 执行秒杀下单，成功后返回订单号  | 是             |

### 接口文档

项目集成 Knife4j / OpenAPI，启动后访问：

```text
http://localhost:8080/doc.html
```

OpenAPI JSON 地址：

```text
http://localhost:8080/v3/api-docs
```

## 示例请求

### 用户注册

```http
POST http://localhost:8080/user/register
Content-Type: application/json

{
  "username": "test_user",
  "password": "123456"
}
```

### 用户登录

```http
POST http://localhost:8080/user/login
Content-Type: application/json

{
  "username": "test_user",
  "password": "123456"
}
```

登录成功后，将返回的 token 放入后续请求头：

```http
Authorization: Bearer <token>
```

### 查询商品列表

```http
GET http://localhost:8080/goods/list?page=1&size=10&keyword=Product
Authorization: Bearer <token>
```

### 查询商品详情

```http
GET http://localhost:8080/goods/1001
Authorization: Bearer <token>
```

### 创建普通订单

```http
POST http://localhost:8080/order/create
Content-Type: application/json
Authorization: Bearer <token>

{
  "goodsId": 1001,
  "quantity": 1
}
```

### 查询秒杀商品详情

```http
GET http://localhost:8080/seckill/goods/1
Authorization: Bearer <token>
```

### 执行秒杀

```http
POST http://localhost:8080/seckill/do/1
Authorization: Bearer <token>
```

成功时返回订单号，例如：

```json
{
  "code": 200,
  "msg": "success",
  "data": "SECKILL1710000000000123"
}
```

## 快速开始

### 1. 克隆项目

```bash
git clone <your-repository-url>
cd seckill
```

### 2. 配置环境变量

项目根目录已提供 `.env` 示例，用于 Docker Compose 读取 MySQL、Redis 和 RabbitMQ 参数。

示例：

```env
MYSQL_ROOT_PASSWORD=123456
MYSQL_DATABASE=seckill_db
MYSQL_USER=root
MYSQL_PASSWORD=123456

REDIS_PASSWORD=

RABBITMQ_DEFAULT_USER=seckill
RABBITMQ_DEFAULT_PASS=seckill123
RABBITMQ_DEFAULT_VHOST=/
```

### 3. 使用 Docker Compose 启动

```bash
docker compose up -d --build
```

启动完成后，会自动创建并启动以下服务：

| 服务                | 地址                     |
| ------------------- | ------------------------ |
| 后端服务            | `http://localhost:8080`  |
| MySQL               | `localhost:3306`         |
| Redis               | `localhost:6379`         |
| RabbitMQ            | `localhost:5672`         |
| RabbitMQ Management | `http://localhost:15672` |

RabbitMQ 管理后台默认账号密码来自 `.env`：

```text
username: seckill
password: seckill123
```

### 4. 查看服务状态

```bash
docker compose ps
```

### 5. 查看后端日志

```bash
docker compose logs -f app
```

### 6. 访问接口文档

```text
http://localhost:8080/doc.html
```

## 本地开发运行

如果希望在 IntelliJ IDEA 中直接运行后端服务，可以只用 Docker 启动 MySQL、Redis 和 RabbitMQ，再在 IDEA 中运行启动类。

启动类：

```text
HighConcurrencySeckillApplication
```

由于 `application.yml` 默认使用 Docker Compose 内部服务名：

```yaml
MYSQL_HOST: mysql
REDIS_HOST: redis
RABBITMQ_HOST: rabbitmq
```

本地 IDEA 运行时，需要将这些环境变量改为 localhost，例如：

```text
MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_DATABASE=seckill_db
MYSQL_USER=root
MYSQL_PASSWORD=123456
REDIS_HOST=localhost
REDIS_PORT=6379
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_USER=seckill
RABBITMQ_PASS=seckill123
RABBITMQ_VHOST=/
```

也可以直接修改本地运行配置中的 Environment variables，不建议把个人本地配置硬编码到 `application.yml`。

## 项目结构

```text
seckill
├── compose.yaml
├── Dockerfile
├── pom.xml
├── docker
│   └── mysql
│       └── init
│           └── 01-init.sql
└── src
    └── main
        ├── java
        │   └── com/example/high_concurrency_seckill
        │       ├── common
        │       ├── config
        │       ├── controller
        │       ├── converter
        │       ├── dto
        │       ├── entity
        │       ├── mapper
        │       ├── service
        │       ├── utils
        │       └── vo
        └── resources
            └── application.yml
```

## 设计亮点

### Redis 前置抗流量冲击

秒杀库存预先加载到 Redis，秒杀请求先在 Redis 层完成库存判断和扣减，避免高并发请求直接访问 MySQL。Redis Lua 脚本保证库存扣减的原子性，降低超卖风险。

### RabbitMQ 异步下单削峰

秒杀接口只完成资格校验、库存预扣和消息投递，然后快速返回订单号。真正的订单创建由 RabbitMQ 消费端异步完成，从而削平瞬时流量峰值，降低数据库写入压力。

### 原子化秒杀与一人一单控制

秒杀入口通过单个 Redis Lua 脚本原子完成「校验时间段 + 一人一单查重 + 扣减库存 + 占位」，Redis Set 记录用户参与状态，数据库唯一索引最终兜底。查重与占位在同一原子操作内，共同降低重复下单风险。

### 消息可靠性与补偿机制

项目开启 RabbitMQ publisher confirm 和 return callback，并在 confirm 失败或发送异常时执行重试与 Redis 补偿。消费端配置重试机制，重试耗尽后进入死信队列，并在死信消费中恢复 Redis 库存和用户参与记录。

### 缓存穿透与缓存雪崩防护

秒杀商品详情查询使用 Redis 缓存热点数据。对于不存在的秒杀商品，系统写入短 TTL 空对象，减少无效请求穿透到数据库；对于正常商品缓存，设置随机过期时间，降低大量 key 同时失效的风险。

### 统一接口规范与接口文档

系统使用统一 `Result<T>` 响应结构，并通过全局异常处理返回稳定的错误格式。同时接入 Knife4j / OpenAPI，便于在浏览器中查看和调试接口。

## 测试建议

### 自动化测试

项目内置一套核心自动化回归测试（25 个用例），基于 Testcontainers 拉起隔离的 MySQL / Redis / RabbitMQ，覆盖秒杀业务核心不变量：

| 测试类 | 覆盖内容 |
|---|---|
| `SeckillLuaTest` | Redis Lua 原子预占：正常 / 重复请求 / 库存 0 / 活动未开始 / 已结束 + 补偿幂等 |
| `SeckillOrderConsumerTest` / `RollbackTest` | 消费落库、重复与并发重复消费、DB 库存不足漂移校准、下游异常整事务回滚 |
| `SeckillReconciliationTest` | 对账收敛：DB 有单不补偿、悬挂重投、重试耗尽补偿、终态不处理、库存漂移校准 |
| `HttpSemanticsTest` | HTTP 异常语义（404 / 409 / 500） |
| `SnowflakeIdUtilTest` / `SeckillStockInitTest` | 雪花 ID 唯一性、活动库存初始化与僵尸库存清理 |

运行方式（需 Docker Desktop 运行中，全部使用本地镜像，无需联网）：

```bash
./mvnw test                          # 全量
./mvnw -Dtest=SeckillLuaTest test     # 单个测试类
```

此外，故障注入测试（B1~B7，见 `docs/failpoint/`）与 JMeter 压测（见 `docs/压测报告与面试问答.md`）作为补充，共同构成完整回归体系。

### 手工功能测试

项目也适合按以下顺序进行手工功能测试：

1. 启动 Docker Compose，确认 MySQL、Redis、RabbitMQ 和 app 均为 healthy 或 running。
2. 访问 `/hello`，确认后端服务正常。
3. 访问 `/doc.html`，确认接口文档正常加载。
4. 调用 `/user/register` 或 `/user/login` 获取 token。
5. 携带 `Authorization: Bearer <token>` 查询商品列表和商品详情。
6. 查询秒杀商品详情，观察 Redis 是否写入商品缓存和库存 key。
7. 调用 `/seckill/do/{seckillGoodsId}` 执行秒杀，确认接口快速返回订单号。
8. 查看 RabbitMQ 队列消费情况和 MySQL `order` 表是否生成订单。
9. 重复请求同一秒杀商品，验证一人一单限制。
10. 使用 JMeter 或其他压测工具对秒杀接口进行并发测试，观察库存、订单数量和重复订单情况。

## 后续规划

后续将继续扩展以下能力：

- 增加订单支付、取消和超时关闭流程
- 引入延迟队列处理订单超时未支付
- 增加秒杀接口限流和用户级频控
- 引入 Sentinel 或 Redis Cluster 提升缓存高可用能力
- 引入 Prometheus、Grafana 监控
- 增加管理员后台接口，用于维护商品和秒杀活动
- 增加前端页面，完成完整秒杀业务闭环

## License

本项目暂未指定开源许可证。