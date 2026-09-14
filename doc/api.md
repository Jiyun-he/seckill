# API 参考

[← 返回 README](../README.md)

启动后可在 `http://localhost:8080/doc.html`（Knife4j）交互式浏览与调试全部接口，OpenAPI JSON 位于 `http://localhost:8080/v3/api-docs`。本文是快速索引，字段级说明以接口文档为准。

## 目录

- [响应格式](#响应格式)
- [认证](#认证)
- [接口清单](#接口清单)
- [示例](#示例)

## 响应格式

所有接口返回统一包装：

```json
{
  "code": 200,
  "msg": "success",
  "data": {}
}
```

失败时 `code` 与 HTTP 状态码一致，`data` 为 `null`：

| HTTP 状态码 | 场景 |
| --- | --- |
| 400 | 参数校验失败 |
| 401 | 未携带 Token 或 Token 失效 |
| 403 | 不在秒杀时间段内 |
| 404 | 资源不存在 |
| 409 | 已参与过该秒杀、库存不足 |
| 429 | 系统繁忙 |
| 500 | 库存未初始化、库存数据异常、系统异常 |

## 认证

除下列路径外，所有接口都经过 `LoginInterceptor` 校验：

```text
/user/login  /user/register  /hello
/doc.html  /swagger-ui/**  /v3/api-docs/**  /webjars/**  /actuator/**  /fault/**
```

认证方式为请求头携带 JWT：

```http
Authorization: Bearer <token>
```

Token 由注册或登录接口返回，同时在 Redis 中保留登录态记录。

## 接口清单

### 健康检查

| Method | Path | 说明 | 需要 Token |
| --- | --- | --- | --- |
| GET | `/hello` | 服务存活检查 | 否 |
| GET | `/test/db` | 数据库连通性与用户表查询 | 是 |

### 用户

| Method | Path | 说明 | 需要 Token |
| --- | --- | --- | --- |
| POST | `/user/register` | 注册，返回 JWT Token | 否 |
| POST | `/user/login` | 登录，返回 JWT Token | 否 |

### 商品

| Method | Path | 说明 | 需要 Token |
| --- | --- | --- | --- |
| GET | `/goods/list` | 分页查询商品列表，支持 `keyword` 模糊搜索 | 是 |
| GET | `/goods/{id}` | 商品详情 | 是 |

`/goods/list` 查询参数：`page`（默认 1）、`size`（默认 10）、`keyword`（可选）。

### 普通订单

| Method | Path | 说明 | 需要 Token |
| --- | --- | --- | --- |
| POST | `/order/create` | 创建普通商品订单，同步落库 | 是 |

### 秒杀

| Method | Path | 说明 | 需要 Token |
| --- | --- | --- | --- |
| GET | `/seckill/goods/{id}` | 秒杀商品详情，带 Redis 缓存 | 是 |
| POST | `/seckill/do/{seckillGoodsId}` | 执行秒杀，返回订单号（不等待落库） | 是 |
| GET | `/seckill/order/{orderNo}` | 查询秒杀订单处理状态 | 是 |

`POST /seckill/do/{seckillGoodsId}` 返回的订单号仅代表请求已受理，订单此时尚未写入数据库。需要确认最终结果时轮询 `GET /seckill/order/{orderNo}`：

| 返回状态 | 含义 |
| --- | --- |
| `PROCESSING` | 处理中（PENDING / CONFIRMED / RETRY 的对外合并视图） |
| `CONSUMED` | 已成功落库 |
| `FAILED` | 最终失败，库存已补偿或校准 |
| `NOT_FOUND` | Redis 无状态记录且数据库无订单 |

对照实现见[一致性设计 § 订单状态机](consistency.md#订单状态机)。

### 故障注入

以下接口仅在 `fault-test` profile 下存在（`FaultInjectionController` 标注 `@Profile("fault-test")`），默认配置下不会注册：

| Method | Path | 说明 |
| --- | --- | --- |
| POST | `/fault/{id}/block` | 让指定埋点阻塞 |
| POST | `/fault/{id}/throw` | 让指定埋点抛出异常 |
| POST | `/fault/{id}/release` | 释放阻塞的线程 |
| POST | `/fault/{id}/disable` | 关闭埋点 |
| GET | `/fault/{id}` | 查询单个埋点状态 |
| GET | `/fault` | 查询全部埋点状态 |

可用埋点标识见 [测试 § 故障注入](testing.md#故障注入)。

## 示例

### 注册

```http
POST http://localhost:8080/user/register
Content-Type: application/json

{
  "username": "test_user",
  "password": "123456"
}
```

### 登录

```http
POST http://localhost:8080/user/login
Content-Type: application/json

{
  "username": "test_user",
  "password": "123456"
}
```

两个接口都返回 Token：

```json
{
  "code": 200,
  "msg": "success",
  "data": "<jwt-token>"
}
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

成功时 `data` 为雪花算法生成的订单号（`Long`）：

```json
{
  "code": 200,
  "msg": "success",
  "data": 1767225600000001
}
```

### 查询秒杀订单状态

```http
GET http://localhost:8080/seckill/order/1767225600000001
Authorization: Bearer <token>
```

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "orderNo": 1767225600000001,
    "status": "CONSUMED"
  }
}
```
