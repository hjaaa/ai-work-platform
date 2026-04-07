# 方案

## 策略
采用 Spring Boot 标准的 profile 机制：
- `application.yml` — 公共配置（不含环境差异）
- `application-dev.yml` — 开发环境（本地 MySQL/Redis，DEBUG 日志）
- `application-test.yml` — 测试环境（测试服务器地址，INFO 日志）
- `application-prod.yml` — 生产环境（生产服务器地址，WARN 日志，更严格的并发控制）

logback-spring.xml 通过 `<springProfile>` 标签区分：
- dev: 控制台彩色 + DEBUG + 不写文件（开发快速反馈）
- test: 控制台 + 文件 + INFO
- prod: 仅文件 + WARN + 更大保留量
