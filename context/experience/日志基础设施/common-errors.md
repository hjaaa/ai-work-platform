## 2026-04-07 新建 Spring Boot 项目遗漏日志基础设施

- **现象**：项目代码中已使用 SLF4J Logger，但没有 logback-spring.xml，没有 traceId，没有脱敏，只有默认控制台输出
- **原因**：AI 初始化项目时只关注业务代码，忽略了日志基础设施属于"项目初始化必做项"
- **正确做法**：Maven 多模块项目初始化阶段（Phase 1）就应包含 logback-spring.xml + TraceIdFilter + DesensitizeConverter
- **适用场景**：任何新建 Spring Boot 项目

## 2026-04-07 WebSocket 不走 Servlet Filter 需要单独处理 traceId

- **现象**：TraceIdFilter 只拦截 HTTP 请求，WebSocket STOMP 消息没有 traceId
- **原因**：WebSocket 升级后不再走 Servlet Filter 链，需要通过 Spring Messaging 的 ChannelInterceptor 处理
- **正确做法**：实现 `ChannelInterceptor`，在 `preSend` 中注入 MDC traceId，在 `afterSendCompletion` 中清理
- **适用场景**：所有使用 WebSocket + STOMP 并且需要链路追踪的项目

## 2026-04-07 配置文件未做多环境拆分

- **现象**：所有环境配置（数据库密码、服务器地址、并发数）全部写在一个 application.yml 中，包括明文密码
- **原因**：AI 初始化项目时用"先跑通"思路把所有配置堆在一起，没有同步考虑环境隔离
- **正确做法**：项目初始化阶段就应拆分 application.yml（公共）+ application-dev/test/prod.yml（差异），生产密码用 `${ENV_VAR}` 占位
- **适用场景**：任何新建 Spring Boot 项目
