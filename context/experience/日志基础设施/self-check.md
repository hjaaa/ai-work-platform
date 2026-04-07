## Spring Boot 项目日志自检清单

- [ ] 是否有 `logback-spring.xml`？不能只靠 application.yml 的 `logging.level` 配置
- [ ] 日志是否包含 traceId？需要 Servlet Filter + MDC 注入，WebSocket 需要单独的 ChannelInterceptor
- [ ] 日志输出是否脱敏？手机号/身份证/银行卡需要通过 Logback 自定义 Converter 自动处理
- [ ] 是否有文件输出 + 滚动策略？控制台日志重启就丢，必须写文件
- [ ] ERROR 日志是否单独输出一份？方便线上快速定位问题
- [ ] 是否按 dev/test/prod 拆分了 application-{profile}.yml？公共配置放主 yml，差异配置放 profile yml
- [ ] 生产环境敏感配置（密码、密钥）是否用 `${ENV_VAR}` 占位？禁止明文写死
- [ ] logback-spring.xml 是否按 `<springProfile>` 区分了不同环境的日志策略？
