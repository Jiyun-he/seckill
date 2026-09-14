# Docker 资源

[← 返回 README](../README.md)

## 目录

- [mysql/init](#mysqlinit)
- [compose.fault-test.yaml](#composefault-testyaml)

## mysql/init

`mysql/init/01-init.sql` 由根目录 [`compose.yaml`](../compose.yaml) 以只读方式挂载到容器的 `/docker-entrypoint-initdb.d`，MySQL 官方镜像会在**初始化数据目录时**按文件名顺序执行该目录下的脚本。

脚本内容：

1. 按依赖顺序 `DROP TABLE`，再建四张表：`user`、`goods`、`seckill_goods`、`order`
2. 写入演示数据：9 条普通商品、5 条秒杀商品（秒杀商品 1~5 分别关联商品 1001~1005，库存 50 / 29 / 100 / 20 / 75，活动有效期覆盖 2026 全年）

表结构与索引的完整说明见[数据模型](../doc/data-model.md)。

### 注意

- 该脚本**只在数据卷为空时执行一次**。修改脚本后不会自动生效，需要先清除数据卷：

  ```bash
  docker compose down -v
  docker compose up -d
  ```

- 脚本开头的 `DROP TABLE IF EXISTS` 意味着它会**清空已有数据**，仅适用于开发与演示环境。
- 秒杀商品的活动时间写死为 `2026-01-01 ~ 2026-12-31`。若当前日期不在该区间内，秒杀接口会返回 403（不在秒杀时间段内），需要先修改 `seckill_goods` 的 `start_time` / `end_time` —— 注意活动配置在开始后视为冻结，见[交易配置冻结](../doc/consistency.md#交易配置冻结)。
- 测试用的建库脚本是独立的 [`src/test/resources/db/init.sql`](../src/test/resources/db/init.sql)，由 Testcontainers 加载，与本文件互不影响。

## compose.fault-test.yaml

这是一个**叠加文件**，不能单独使用。它只做一件事：给 app 服务注入 `SPRING_PROFILES_ACTIVE: fault-test`，从而激活故障注入埋点与 `/fault/**` 控制接口。

```bash
docker compose -f compose.yaml -f compose.fault-test.yaml up -d --build
```

不加该文件时应用以默认 profile 运行，`failpointService` 由 `NoopFailpointService` 提供，所有埋点为空实现。

埋点标识、脚本用法与可验证的失败窗口见[测试 § 故障注入](../doc/testing.md#故障注入)与 [`test/README.md`](../test/README.md)。
