# 贡献指南

感谢你愿意改进 High Concurrency Seckill。仓库将「交易正确性」放在性能之前：任何涉及库存、幂等、MQ 或补偿的变更，都应说明它维护了哪个不变式，并附上可重现的测试。

## 开发环境

- JDK 17
- Docker Desktop 或 Docker Engine
- 仓库自带的 Maven Wrapper

首次启动：

```bash
cp .env.example .env
docker compose up -d --build
```

PowerShell 使用 `Copy-Item .env.example .env`。更完整的环境说明见 [开发与部署](doc/development.md)。

## 提交一个改动

1. 从 `main` 创建单一目的的分支，例如 `feat/rate-limit`、`fix/reconcile-lock` 或 `docs/architecture`。
2. 尽量让每个 commit 可独立理解、可独立回滚，不要混合无关的格式化与业务变更。
3. 更新或新增测试，同步修改受影响的文档。
4. 提交前执行 `./mvnw spotless:apply` 和 `./mvnw verify`。
5. Pull Request 中说明背景、变更、验证证据与风险。

## Java 代码风格

仓库使用 Spotless + google-java-format AOSP 风格作为唯一格式化结果。

```bash
./mvnw spotless:apply   # 自动修复格式
./mvnw spotless:check   # 只检查，不修改文件
```

Windows PowerShell 下将 `./mvnw` 替换为 `.\mvnw.cmd`。

CI 在 Maven `validate` 阶段执行格式检查。除自动格式化外，请遵守以下约定：

- 生产代码标识符使用英文；类名使用 `UpperCamelCase`，方法和变量使用 `lowerCamelCase`，常量使用 `UPPER_SNAKE_CASE`。
- 缩写作为一个完整单词处理，例如 `RabbitMQConfiguration`、`JwtUtil`。
- Spring 依赖使用构造器注入，依赖字段声明为 `final`；不在生产代码中使用字段 `@Autowired`。
- 禁止 wildcard import；删除未使用 import。
- Controller 只负责 HTTP 语义与参数转换，业务不变式放在 Service，数据访问放在 Mapper。
- 对外请求使用 DTO，对外响应使用 VO；不直接暴露带敏感字段的 Entity。
- 日志使用参数化 SLF4J，不使用 `System.out` 或 `printStackTrace()`。
- 注释说明「为什么」和失败边界，不重复代码本身已经表达的内容。
- 修改秒杀链路时，必须检查不超卖、一人一单、幂等消费、幂等补偿与终态不回退。

## 测试约定

- 修复缺陷时先添加能够复现问题的回归用例。
- 不依赖测试顺序，每个用例都应从可预期状态开始。
- 业务断言优先描述不变式，而不是实现细节。
- 涉及 Redis / RabbitMQ / MySQL 协作时，优先使用 Testcontainers 集成测试。
- 若修改失败处理，应补充或更新 Failpoint 场景。

## Commit 规范

提交消息遵循 [Conventional Commits 1.0.0](https://www.conventionalcommits.org/zh-hans/v1.0.0/)：

```text
<type>(<scope>): <description>
```

允许的 `type`：

| Type | 用途 |
| --- | --- |
| `feat` | 新功能 |
| `fix` | 缺陷修复 |
| `refactor` | 不改变对外行为的重构 |
| `perf` | 性能优化 |
| `test` | 测试新增或调整 |
| `docs` | 仅文档变更 |
| `build` | 构建系统或依赖 |
| `ci` | CI 工作流 |
| `style` | 不影响语义的格式调整 |
| `chore` | 其他维护性变更 |
| `revert` | 回滚已有提交 |

建议的 `scope` 包括 `api`、`auth`、`seckill`、`mq`、`redis`、`db`、`reconcile`、`docs`、`infra` 和 `test`。`scope` 可省略，但同一系列提交应保持一致。

示例：

```text
feat(seckill): add user-level rate limiting
fix(reconcile): avoid deleting another worker's lock
test(mq): cover duplicate delivery compensation
docs(readme): clarify asynchronous order status
refactor(config): standardize configuration class names
```

描述应简短、明确，不以句号结尾。破坏性变更使用 `!` 并在 footer 中说明迁移方式：

```text
feat(api)!: change order status response

BREAKING CHANGE: clients must read status from data.status.
```

可选地启用仓库内置的 commit 模板：

```bash
git config commit.template .gitmessage
```

CI 会校验本次 push 或 Pull Request 新增的非 merge commit。本地也可检查任意 commit 区间：

```bash
bash .github/scripts/check-commit-messages.sh HEAD~3 HEAD
```

## Pull Request 检查清单

- [ ] 变更范围单一，没有夹带无关重构
- [ ] `./mvnw spotless:check` 通过
- [ ] `./mvnw verify` 通过，或已说明无法执行的环境原因
- [ ] 新行为或缺陷修复有对应测试
- [ ] README / API / 架构文档已与代码同步
- [ ] 没有提交凭据、token、压测产物或 IDE 文件
- [ ] 涉及交易链路时，已说明一致性风险与回滚方案
