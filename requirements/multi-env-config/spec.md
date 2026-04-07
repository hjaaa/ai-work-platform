# 多环境配置

将 application.yml 和 logback-spring.xml 拆分为 dev/test/prod 三个环境，差异点包括：
- 数据库/Redis 连接信息
- 日志级别和输出策略
- AI 编排器配置（CLI 路径、并发数）
- 部署目标服务器
