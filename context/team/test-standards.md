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

## TDD 顺序（强制，不可跳过）

**必须先写测试，再写实现。顺序不可逆，无例外。**

每次新增或修改 Service 层逻辑，必须严格按以下步骤执行：

1. 定义接口/方法签名
2. 编写单元测试（正常路径 + 边界 + 异常）
3. 运行测试，确认**红灯**（测试失败，证明测试有效）
4. 编写业务实现
5. 运行测试，确认**绿灯**（测试通过）

**禁止行为**：
- 先改业务代码，再补测试
- 多个 task 攒到最后统一补测试
- 以"改动很小"为由跳过红灯步骤

## 执行要求

- 测试必须能通过 `mvn test` 或 `gradle test` 独立运行
- 不依赖外部环境（数据库、Redis、MQ 等）
