# 实施方案：日志基础设施

## 实现内容
1. `logback-spring.xml` — 控制台彩色输出 + logs/ 目录文件输出 + 按天滚动保留 30 天
2. `TraceIdFilter` — Servlet Filter，请求入口生成 traceId 放入 MDC，响应头也带上
3. `DesensitizeConverter` — Logback PatternLayout converter，正则匹配并脱敏手机号/身份证/银行卡
4. 更新 application.yml 移除简单 logging 配置（由 logback-spring.xml 接管）
