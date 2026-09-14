# 测试脚本

[← 返回 README](../README.md)

本目录包含秒杀链路的**压力测试**与**故障注入**脚本，均为辅助工具，不参与 `mvn` 构建。测试的定位与整体说明见[测试文档](../doc/testing.md)。

## 目录

- [前置条件](#前置条件)
- [压测脚本](#压测脚本)
- [JMeter 场景](#jmeter-场景)
- [故障注入脚本](#故障注入脚本)
- [典型流程](#典型流程)
- [产物与忽略规则](#产物与忽略规则)

## 前置条件

- 应用已启动并可访问 `http://localhost:8080`（见 [README 快速开始](../README.md#快速开始)）
- Python 3，依赖 `pymysql`、`redis`、`requests`
- Apache JMeter 5.6.x（仅命令行模式）

Python 相关的数据库与中间件连接参数目前直接写在脚本顶部（`localhost` + `.env` 中的默认账号密码），与项目默认配置一致。

### 需要先改的两处本机路径

脚本和 JMX 场景中存在**硬编码的本机绝对路径**，在别的机器上运行前必须修改：

| 文件 | 硬编码内容 |
| --- | --- |
| `run_performance.py`、`run_consistency.py`、`run_concurrency_test.py` | `JMETER = r"F:\someSoftwares\apache-jmeter-5.6.3\apache-jmeter-5.6.3\bin\jmeter.bat"` |
| `seckill_test.jmx`、`scene1~3_*.jmx` | CSV 输入（`users.csv`、`single_user.csv`）与 `.jtl` 输出均写死为 `d:/learning/java/seckill/test/...` |

JMX 中的路径可直接改为相对路径（相对于执行 JMeter 时的工作目录），或按需替换为你的实际路径。

## 压测脚本

| 脚本 | 用途 |
| --- | --- |
| `init_pressure.py` | 压测数据初始化：清订单、重置库存、清 Redis 秒杀 key、清 MQ、重启 app 触发预热，可选批量造用户 |
| `register_users.py` | 批量注册用户并把 `username` + `token` 导出为 CSV，供 JMeter 的 CSV Data Set Config 读取 |
| `monitor.py` | 压测期间每秒采集 DB 连接池 / RabbitMQ 队列 / Redis 指标，写入 CSV |
| `analyze_monitor.py` | 分析 `monitor.py` 输出的 CSV，统计各项峰值与均值 |
| `verify_pressure.py` | 压测后一致性核验：一键检查 DB / Redis / MQ，输出 PASS/FAIL |
| `run_performance.py` | 性能阶梯压测：大库存 + 阶梯并发，每梯度从 `.jtl` 计算 QPS / P99 / 错误率 |
| `run_consistency.py` | 一致性压测：3 场景 × 3 轮，每轮「初始化 → JMeter → 核验」全自动 |
| `run_concurrency_test.py` | 消费并发扩展实验：逐档调大 consumer 并发，记录落库 TPS / publish 速率 / MQ 积压 |

常用参数：

```bash
python test/register_users.py --count 5000
python test/init_pressure.py --stock 100000 --users 100000 --clear-users   # 首次完整初始化
python test/init_pressure.py --stock 100000                                # 每轮重置，保留用户与 token
python test/run_performance.py --gradients 20,50,100,200,500 --runs 3 --duration 60
python test/verify_pressure.py --initial-stock 100 --goods-id 1
```

### verify_pressure.py 的核验项

压后核验覆盖以下不变量，任一失败即报 FAIL：

- DB 秒杀库存非负
- 订单数不超过初始库存
- 初始库存 − 终态库存 = 订单数
- 无重复有效订单（按 `user_id, seckill_goods_id` 分组无 count > 1）
- 无悬挂中间态订单
- `order` 表中不存在假库存（Redis 与 DB 库存一致）
- 死信队列为空

## JMeter 场景

| 文件 | 场景 | 默认并发 |
| --- | --- | ---: |
| `seckill_test.jmx` | 通用压测：随机秒杀商品 ID，用于性能阶梯与消费并发实验 | 100 |
| `scene1_many_users_few_stock.jmx` | 一致性场景 1：多人抢少量库存 | 1000 |
| `scene2_single_user_repeat.jmx` | 一致性场景 2：同一用户疯狂重复请求 | 500 |
| `scene3_mixed_repeat.jmx` | 一致性场景 3：多用户混合重复请求（每用户随机 1~5 次） | 500 |

线程数与持续时间通过 JMeter 属性注入，无需改文件：

```bash
jmeter -n -t test/seckill_test.jmx -Jthreads 500 -Jduration 60
```

场景 1、3 读取 `users.csv`（多用户），场景 2 读取 `single_user.csv`（单用户）。两者由 `register_users.py` 生成。

## 故障注入脚本

应用需以 `fault-test` profile 运行：

```bash
docker compose -f compose.yaml -f compose.fault-test.yaml up -d --build
```

| 脚本 | 用途 |
| --- | --- |
| `failpoint/reset.py` | 复位：清空订单、重置库存、purge MQ，并重启 app 触发预热 |
| `failpoint/fault.py` | 控制埋点：`block` / `throw` / `release` / `disable` / `status` / `status-all` |
| `failpoint/observe.py` | 观测：打印 Redis / MySQL / RabbitMQ 关键状态快照 |

```bash
python test/failpoint/reset.py
python test/failpoint/fault.py block commit_after          # 启用 BLOCK
python test/failpoint/fault.py status commit_after --wait  # 轮询直到有线程被拦住
python test/failpoint/observe.py                           # 观察当前状态
python test/failpoint/fault.py release commit_after        # 释放
```

可用埋点标识与各自对应的失败窗口见[测试 § 故障注入](../doc/testing.md#故障注入)。

## 典型流程

一致性压测（全自动）：

```bash
python test/init_pressure.py --stock 100000 --users 100000 --clear-users
python test/run_consistency.py
```

性能阶梯压测（`monitor.py` 需另开一个终端常驻）：

```bash
python test/init_pressure.py --stock 20000 --all-goods
python test/monitor.py            # 另开终端运行，压测结束后 Ctrl+C
python test/run_performance.py --gradients 20,50,100,200,500,800,1000
```

消费并发扩展实验（脚本自行重启 app）：

```bash
python test/run_concurrency_test.py
```

故障注入单窗口验证：

```bash
python test/failpoint/reset.py
python test/failpoint/fault.py block commit_after
curl -X POST localhost:8080/seckill/do/1 -H "Authorization: Bearer <token>"
python test/failpoint/fault.py status commit_after --wait
python test/failpoint/observe.py
docker compose -f compose.yaml -f compose.fault-test.yaml kill app
docker compose -f compose.yaml -f compose.fault-test.yaml start app
python test/failpoint/observe.py    # 对比 kill 前后，确认幂等检查拦住重投
```

## 产物与忽略规则

以下为压测产物，已被根目录 `.gitignore` 忽略，不会入库：

```text
test/*.jtl                   JMeter 采样结果
test/*.log                   脚本运行日志
test/monitor_perf*.csv       monitor.py 采集数据
test/users.csv               注册用户导出的 token 表
test/single_user.csv         单用户 token 表
test/_gen_single.py          本地临时脚本
```

`*.jtl` 由 JMeter 直接写入；`users.csv` 与 `single_user.csv` 因内含真实 JWT Token 而不入库，运行前需自行用 `register_users.py` 生成。
