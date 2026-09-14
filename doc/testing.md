# 测试

[← 返回 README](../README.md)

项目有三层验证手段，分别回答不同的问题：

| 层次 | 手段 | 回答的问题 |
| --- | --- | --- |
| 自动化回归 | 基于 Testcontainers 的集成测试 | 代码改动后核心不变量是否还成立 |
| 故障注入 | Failpoint 埋点 + 外部故障 | 精确失败窗口是否被正确兜住 |
| 压力测试 | JMeter + 监控采集 | 容量拐点在哪、瓶颈是什么 |

## 目录

- [自动化测试](#自动化测试)
- [故障注入](#故障注入)
- [压力测试](#压力测试)
- [手工验证](#手工验证)

## 自动化测试

测试基于 Testcontainers 拉起隔离的 MySQL / Redis / RabbitMQ，不依赖本机环境，也不会污染开发数据。基类 `AbstractIntegrationTest` 在静态初始化块中启动容器（由 JVM 关闭钩子停止，整个套件共享一套中间件），并在每个用例前清空 Redis、重建 schema、重新预热库存。

### 用例分布

共 28 个用例：

| 测试类 | 用例数 | 覆盖内容 |
| --- | ---: | --- |
| `SeckillLuaTest` | 10 | Lua 原子预占的正常 / 重复用户 / 库存为 0 / 活动未开始 / 活动已结束；补偿的正常恢复与重复补偿幂等；状态机终态权威（PENDING 升 CONFIRMED、CONSUMED 与 FAILED 不被晚到回调覆盖） |
| `SeckillReconciliationTest` | 5 | 对账收敛：悬挂且 DB 已有订单 → 修正且不补偿；悬挂且无订单 → 未耗尽重投并累加 retryCount；耗尽 → 补偿；终态不重复处理；库存对账按 DB 校准 |
| `SeckillOrderConsumerTest` | 4 | 消费落库、重复消费幂等、并发重复消费、DB 库存不足时的漂移校准 |
| `HttpSemanticsTest` | 3 | HTTP 异常语义：404 / 409 / 500 |
| `SeckillStockInitTest` | 2 | 启动预热写入带活动版本的库存与活动时间；清理旧版僵尸 key |
| `SnowflakeIdUtilTest` | 2 | 批量生成无重复、不同 workerId 不碰撞 |
| `SeckillOrderConsumerRollbackTest` | 1 | 下游异常时整事务回滚 |
| `HighConcurrencySeckillApplicationTests` | 1 | Spring 上下文加载 |

### 运行

需要 Docker 守护进程运行中：

```bash
./mvnw test                        # 全量
./mvnw -Dtest=SeckillLuaTest test  # 单个测试类
```

`src/test/resources/testcontainers.properties` 中禁用了 Ryuk（资源回收容器）与镜像更新检查，并直接使用 `mysql:8.0` / `redis:6` / `rabbitmq:management` 三个本地镜像 tag。如果你所在环境可以正常访问 Docker Hub，也可以去掉该文件以启用 Ryuk。

## 故障注入

`fault` 包提供埋点（Failpoint）机制，用于在被测链路的**精确位置**注入失败，验证防护是否有效。埋点默认全部 No-Op：只有 `fault-test` profile 激活时 `DefaultFailpointService` 才生效，其余情况走 `NoopFailpointService`，因此生产配置下不会有任何行为变化。

### 可用埋点

| 埋点标识 | 位置 | 模拟的失败 |
| --- | --- | --- |
| `preoccupy_after` | Redis 预占之后、发送 MQ 之前 | 进程在预占与投递之间崩溃 |
| `publish_after` | 消息 publish 之后、confirm 回调返回之前 | 投递结果未知 |
| `commit_after` | 消费者事务提交之后、ACK 之前 | 已落库但未 ACK |
| `compensate_after` | 死信补偿之后、ACK 之前 | 补偿后崩溃导致重复消费 |
| `insert_before` | 消费者幂等检查之后、INSERT 之前 | 放大 check-then-act 窗口 |

定义见 `Failpoints`。

### 操作方式

启动带故障注入能力的实例：

```bash
docker compose -f compose.yaml -f compose.fault-test.yaml up -d --build
```

通过 HTTP 接口控制埋点（默认配置下这些接口不存在）：

```bash
# 让 commit_after 阻塞，等到有线程被拦住
python test/failpoint/fault.py block commit_after
python test/failpoint/fault.py status commit_after --wait

# 观测当前 Redis / MySQL / RabbitMQ 快照
python test/failpoint/observe.py

# 制造崩溃
docker compose -f compose.yaml -f compose.fault-test.yaml kill app
docker compose -f compose.yaml -f compose.fault-test.yaml start app

# 复位
python test/failpoint/reset.py
```

`block` 会让命中的线程挂起，便于人工执行 `kill` 制造崩溃；`throw` 则直接在埋点处抛出异常。完整脚本说明见 [`test/README.md`](../test/README.md)。

## 压力测试

压测脚本位于 `test/` 目录，覆盖三类场景：

| 类别 | 脚本 | 目的 |
| --- | --- | --- |
| 一致性压测 | `run_consistency.py` | 高并发竞争下业务不变量是否保持 |
| 性能阶梯压测 | `run_performance.py` | 接口吞吐与容量拐点 |
| 消费并发扩展 | `run_concurrency_test.py` | 定位于异步链路的真实瓶颈 |

每轮压测后由 `verify_pressure.py` 做一键核验：DB 库存非负、订单数不超过库存、库存扣减一致、无重复有效订单、无悬挂中间态、无假库存、死信队列为空。

压测**必须同时看两个吞吐**：接口 QPS 反映的是「受理速率」（Redis Lua 预占 + 发 MQ 即返回），订单落库 TPS 才反映真实下单能力，二者由 MQ 积压连接。只看接口 QPS 会严重高估系统容量。

脚本用法与依赖见 [`test/README.md`](../test/README.md)。

## 手工验证

不跑脚本时，可按下列顺序验证主链路：

1. `docker compose up -d` 后确认四个服务均为 healthy 或 running。
2. 访问 `/hello` 确认服务存活。
3. 打开 `/doc.html` 确认接口文档加载正常。
4. 调用 `/user/register` 或 `/user/login` 获取 Token。
5. 携带 `Authorization: Bearer <token>` 查询商品列表与详情。
6. 查询秒杀商品详情，确认 Redis 中写入了商品缓存与库存 key。
7. 调用 `/seckill/do/{seckillGoodsId}`，确认接口快速返回订单号。
8. 立即调用 `/seckill/order/{orderNo}`，观察状态从 `PROCESSING` 收敛到 `CONSUMED`。
9. 查看 RabbitMQ 队列消费情况与 MySQL `order` 表是否生成订单。
10. 重复请求同一秒杀商品，验证一人一单限制返回 409。
