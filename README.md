# High Concurrency Seckill

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.3-6DB33F.svg)](https://spring.io/projects/spring-boot)

基于 Spring Boot 的高并发秒杀系统。核心链路为：Redis Lua 原子预占 → RabbitMQ 异步削峰 → MySQL 最终落库，配合订单状态机、幂等补偿与异常交易对账，在瞬时高并发下保证**不超卖、不重复下单、不丢单**。

秒杀接口把全部资格判定与库存扣减压进一次 Redis Lua 调用，随后异步投递消息并立即返回，同步路径不触碰 MySQL；订单落库由消费端完成，投递失败、结果未知、消费异常等各类失败窗口分别由即时补偿、死信补偿与对账任务兜底。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端框架 | Spring Boot 3.5.3 |
| Web 框架 | Spring Web MVC |
| ORM 框架 | MyBatis-Plus 3.5.15 |
| 数据库 | MySQL 8.0 |
| 缓存 | Redis 6 |
| 消息队列 | RabbitMQ |
| 用户认证 | JWT + Redis |
| 密码加密 | BCrypt |
| 接口文档 | Knife4j / springdoc-openapi |
| 参数校验 | Jakarta Validation |
| 容器化 | Docker / Docker Compose |
| 构建工具 | Maven |
| 开发语言 | Java 17 |

## 核心设计

**Redis Lua 原子预占**
活动时间段校验、一人一单查重、库存扣减、占位登记与预占状态写入在单个 Lua 脚本内完成。查重与占位处于同一原子操作，不存在「先查后写」的竞态窗口；时间校验读预热到 Redis 的活动配置，热路径不访问 MySQL。
→ [一致性设计 § Lua 原子预占](doc/consistency.md#lua-原子预占)

**一人一单三层保障**
Lua 内原子查重 → Redis Set 记录该场次参与用户 → 数据库唯一索引 `uk_user_seckill` 最终兜底。
→ [一致性设计 § 一人一单](doc/consistency.md#一人一单)

**投递可靠性**
开启 publisher confirm 与 return callback，并严格区分「明确失败」与「结果未知」：无法路由、nack、同步异常立即补偿；confirm 超时只标记 `RETRY` 交给对账，避免误补偿已投递的消息。消费端配置重试与死信队列，重试耗尽后进入 DLQ 执行幂等补偿。
→ [一致性设计 § 投递可靠性](doc/consistency.md#投递可靠性)

**订单状态机与终态权威**
预占状态以 Hash 存于 Redis（`PENDING → CONFIRMED → CONSUMED`，异常分支进入 `RETRY` / `FAILED`）。所有中间态转移走原子 CAS 脚本，终态 `FAILED` / `CONSUMED` 不可被晚到的 MQ 回调覆盖。
→ [一致性设计 § 订单状态机](doc/consistency.md#订单状态机)

**异常交易对账**
定时扫描超时中间态记录，以 MySQL 订单为最终事实：已落库则修正状态且禁止补偿，未落库则有限重投、耗尽后补偿。另有每 5 分钟的轻量库存对账。
→ [一致性设计 § 异常交易对账](doc/consistency.md#异常交易对账)

**故障注入测试**
内置 Failpoint 埋点（`fault-test` profile 下生效，默认全部 No-Op），可在精确失败窗口注入阻塞或异常，用于验证上述每条防护是否真的兜住。
→ [测试 § 故障注入](doc/testing.md#故障注入)

## 快速开始

### 1. 克隆并出包

`Dockerfile` 只做 `COPY target/*.jar`，不编译源码，因此必须先本地构建：

```bash
git clone https://github.com/Jiyun-he/seckill.git
cd seckill
./mvnw clean package -DskipTests
```

### 2. 配置环境变量

根目录 `.env` 已提供示例，Docker Compose 会自动读取：

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

完整变量清单见[开发与部署 § 环境变量](doc/development.md#环境变量)。

### 3. 启动

```bash
docker compose up -d --build
```

### 4. 访问

| 服务 | 地址 |
| --- | --- |
| 后端服务 | `http://localhost:8080` |
| 接口文档（Knife4j） | `http://localhost:8080/doc.html` |
| RabbitMQ Management | `http://localhost:15672` |

RabbitMQ 管理后台账号来自 `.env`（默认 `seckill` / `seckill123`）。

### 5. 试一下秒杀

```bash
# 注册并拿到 token
curl -X POST localhost:8080/user/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"123456"}'

# 执行秒杀，返回订单号（此时订单尚未落库）
curl -X POST localhost:8080/seckill/do/1 -H "Authorization: Bearer <token>"

# 轮询订单状态，直到 PROCESSING 收敛为 CONSUMED
curl localhost:8080/seckill/order/<orderNo> -H "Authorization: Bearer <token>"
```

其余命令：

```bash
docker compose ps            # 查看服务状态
docker compose logs -f app   # 跟踪后端日志
```

需要在 IDE 中调试应用时，见[开发与部署 § 本地运行](doc/development.md#本地运行)。

## 文档

| 文档 | 内容 |
| --- | --- |
| [架构与核心链路](doc/architecture.md) | 分层结构、包结构、认证 / 普通下单 / 秒杀下单链路 |
| [数据模型](doc/data-model.md) | 表结构与索引、Redis 键空间、RabbitMQ 拓扑 |
| [一致性设计](doc/consistency.md) | 原子预占、状态机、投递可靠性、补偿校准、对账、缓存防护 |
| [API 参考](doc/api.md) | 接口清单、响应格式、请求示例 |
| [开发与部署](doc/development.md) | 环境变量、Docker 部署、本地运行、项目结构 |
| [测试](doc/testing.md) | 自动化测试、故障注入、压力测试、手工验证 |
| [路线图](doc/roadmap.md) | 后续方向与当前边界 |
| [test/README.md](test/README.md) | 压测与故障注入脚本说明 |

## 测试

**自动化回归**：28 个用例，基于 Testcontainers 拉起隔离的 MySQL / Redis / RabbitMQ，无需本机环境。

```bash
./mvnw test                            # 全量
./mvnw -Dtest=SeckillLuaTest test      # 单个测试类
```

**故障注入**：覆盖 5 个精确失败窗口，验证每条防护路径。

```bash
docker compose -f compose.yaml -f compose.fault-test.yaml up -d --build
python test/failpoint/fault.py block commit_after
python test/failpoint/observe.py
```

**压力测试**：一致性压测、性能阶梯压测、消费并发扩展实验三类，压后由 `verify_pressure.py` 一键核验。详见 [test/README.md](test/README.md)。

## License

[Apache License 2.0](LICENSE)
