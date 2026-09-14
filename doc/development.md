# 开发与部署

[← 返回 README](../README.md)

## 目录

- [环境要求](#环境要求)
- [环境变量](#环境变量)
- [Docker 部署](#docker-部署)
- [本地运行](#本地运行)
- [项目结构](#项目结构)

## 环境要求

| 依赖 | 版本 |
| --- | --- |
| JDK | 17 |
| Maven | 使用仓库自带的 `mvnw`，无需预装 |
| Docker | 含 Docker Compose v2 |

## 环境变量

根目录 `.env` 提供 Docker Compose 读取的示例配置。完整变量清单：

### Compose 读取（宿主机与容器编排）

| 变量 | 说明 | 示例值 |
| --- | --- | --- |
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 | `123456` |
| `MYSQL_DATABASE` | 初始化数据库名 | `seckill_db` |
| `MYSQL_USER` | 应用连接使用的用户名 | `root` |
| `MYSQL_PASSWORD` | 应用连接使用的密码 | `123456` |
| `REDIS_PASSWORD` | Redis 密码，默认留空 | 空 |
| `RABBITMQ_DEFAULT_USER` | RabbitMQ 用户名 | `seckill` |
| `RABBITMQ_DEFAULT_PASS` | RabbitMQ 密码 | `seckill123` |
| `RABBITMQ_DEFAULT_VHOST` | RabbitMQ vhost | `/` |
| `SNOWFLAKE_WORKER_ID` | 雪花算法 workerId，多实例部署时必须互不相同 | `0` |
| `SNOWFLAKE_DATACENTER_ID` | 雪花算法 datacenterId | `0` |
| `SEKKILL_CONSUMER_CONCURRENCY` | 消费端并发数，用于容量调优 | `2` |
| `DB_POOL_SIZE` | HikariCP 最大连接数 | `20` |

### 应用读取（`application.yml`）

| 变量 | 默认值 |
| --- | --- |
| `MYSQL_HOST` / `MYSQL_PORT` | `mysql` / `3306` |
| `MYSQL_DATABASE` / `MYSQL_USER` / `MYSQL_PASSWORD` | `seckill_db` / `root` / `123456` |
| `REDIS_HOST` / `REDIS_PORT` | `redis` / `6379` |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `rabbitmq` / `5672` |
| `RABBITMQ_USER` / `RABBITMQ_PASS` / `RABBITMQ_VHOST` | `seckill` / `seckill123` / `/` |

默认值面向容器内网络（服务名即主机名）。本地直接运行应用时需要把上述 host 全部改为 `localhost`，见[本地运行](#本地运行)。

`JWT_SECRET` 目前未做外部化，密钥与过期时间直接写在 `application.yml` 的 `jwt` 节点下。**部署到任何非本地环境前必须替换默认密钥**。

## Docker 部署

`Dockerfile` 是单阶段构建，只做 `COPY target/*.jar`，**不编译源码**，因此必须先本地出包：

```bash
./mvnw clean package -DskipTests
docker compose up -d --build
```

`docker compose up` 会启动四个服务：

| 服务 | 地址 |
| --- | --- |
| app | `http://localhost:8080` |
| MySQL | `localhost:3306` |
| Redis | `localhost:6379` |
| RabbitMQ | `localhost:5672` |
| RabbitMQ Management | `http://localhost:15672` |

app 容器通过 `depends_on` 的 healthcheck 条件等待 MySQL / Redis / RabbitMQ 全部就绪后才启动。MySQL 首次启动时会执行 [`docker/mysql/init/01-init.sql`](../docker/mysql/init/01-init.sql) 建表并写入演示数据。

常用命令：

```bash
docker compose ps                  # 查看服务状态
docker compose logs -f app         # 跟踪后端日志
docker compose down                # 停止并移除容器（保留数据卷）
docker compose down -v             # 连同数据卷一起清除，下次启动重新初始化
```

需要故障注入能力时叠加 [`compose.fault-test.yaml`](../compose.fault-test.yaml)，它会为 app 注入 `SPRING_PROFILES_ACTIVE: fault-test`：

```bash
docker compose -f compose.yaml -f compose.fault-test.yaml up -d --build
```

## 本地运行

只把中间件跑在 Docker 里，应用在 IDE 中启动，便于断点调试：

```bash
docker compose up -d mysql redis rabbitmq
```

启动类：

```text
com.example.seckill.HighConcurrencySeckillApplication
```

由于 `application.yml` 的默认 host 是容器服务名，本地运行需要在 IDE 的运行配置里覆盖环境变量：

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

不建议把个人本地配置直接改写进 `application.yml`。

## 项目结构

```text
seckill
├── LICENSE
├── README.md
├── compose.yaml                  基础服务编排
├── compose.fault-test.yaml       故障注入 profile 叠加
├── Dockerfile
├── pom.xml
├── doc/                          项目文档
├── docker
│   └── mysql
│       └── init
│           └── 01-init.sql       建表与演示数据
├── src
│   ├── main
│   │   ├── java/com/example/seckill
│   │   │   ├── common            统一响应、异常处理、订单状态枚举
│   │   │   ├── config            Redis / RabbitMQ / MyBatis-Plus / 拦截器 / OpenAPI
│   │   │   ├── controller        REST 接口
│   │   │   ├── converter         Entity → VO
│   │   │   ├── dto               请求参数
│   │   │   ├── entity            表映射
│   │   │   ├── fault             故障注入埋点
│   │   │   ├── mapper            MyBatis-Plus Mapper
│   │   │   ├── service           业务逻辑、MQ 消费者、对账任务
│   │   │   ├── util / utils      雪花 ID、JWT
│   │   │   └── vo                响应视图对象
│   │   └── resources
│   │       └── application.yml
│   └── test
│       ├── java/com/example/seckill    Testcontainers 集成测试
│       └── resources
│           ├── db/init.sql             测试库初始化
│           └── testcontainers.properties
└── test                          压测与故障注入脚本，见 test/README.md
```
