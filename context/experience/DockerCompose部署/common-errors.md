## 2026-04-07 logback-test.xml 是 Logback 保留文件名

- **现象**：Spring Boot JAR 启动时报 "Could not find valid configuration instructions"，即使 logback-spring.xml 配置正确
- **原因**：Logback 自动发现优先级为 `logback-test.xml` > `logback.xml` > `logback-spring.xml`。如果 classpath 中存在 `logback-test.xml`，Logback 直接用它作为主配置，但该文件是 `<included>` 格式，不是 `<configuration>` 格式
- **正确做法**：分环境 logback 文件不能使用 Logback 保留名，应命名为 `logback-spring-dev.xml`、`logback-spring-test.xml`、`logback-spring-prod.xml`
- **适用场景**：任何使用 logback + Spring Boot 分环境配置文件的项目

## 2026-04-07 ClassicConverter 不支持 %xxx(%msg) 括号语法

- **现象**：启动报 "Failed to cast as CompositeConverter for keyword [desensitize]"
- **原因**：`%desensitize(%msg)` 的括号语法是 `CompositeConverter` 专用，`ClassicConverter` 直接从 event 获取消息，不需要括号传参
- **正确做法**：`ClassicConverter` 使用 `%desensitize`（无括号），converter 内部通过 `event.getFormattedMessage()` 获取消息
- **适用场景**：自定义 Logback conversionRule 时
