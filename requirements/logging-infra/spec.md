# 日志基础设施完善

**Created**: 2026-04-07 | **Status**: Draft

## 需求
补充项目缺失的日志基础设施，符合 Constitution 原则 V（可观测性内建）。

## 功能点
1. **logback-spring.xml** — 控制台格式化 + 文件输出 + 按日期/大小滚动
2. **MDC traceId** — 请求入口自动注入 traceId，WebSocket 消息也带上
3. **日志脱敏** — 手机号、身份证、银行卡自动脱敏
