# 测试规范

## 覆盖要求

| 层 | 必须覆盖场景 | 工具 |
|----|-------------|------|
| Service | 正常路径 + ≥1 边界条件 + ≥1 异常路径 | JUnit5 + Mockito |
| Controller | 参数校验失败(400) + 鉴权失败(401/403) + 成功响应 | MockMvc / WebTestClient |

## 命名规范

```
should_xxx_when_yyy
given_yyy_when_xxx_then_zzz
```

示例：
- `should_throwException_when_orderNotFound`
- `given_validUser_when_login_then_returnToken`

## Mock 规则

- 外部依赖（DB、Redis、HTTP、MQ）必须 mock
- 禁止在单元测试中启动 Spring 容器或真实中间件
- 通过 `new XxxService(mockA, mockB)` 构造被测对象

## 执行要求

- 测试必须能通过 `mvn test` 或 `gradle test` 独立运行
- 不依赖外部环境（数据库、Redis、MQ 等）
- TDD 流程：先写测试（Red）→ 写实现（Green）→ 重构（Refactor）
