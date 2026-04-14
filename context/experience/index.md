# 经验索引

> **Agent 必读文件**：每次需求分析或方案设计前，先加载本索引，按关键词匹配后按需加载对应的经验文件。
> 本文件应保持轻量（< 500 tokens），只存放关键词→文件的映射。

## 使用方式

1. Agent 识别用户意图中的关键词
2. 在下表中匹配相关经验文件
3. 按"加载时机"列判断当前是否需要加载
4. 置信度为"低"的条目仅供参考，"高"的条目必须遵守
5. 只加载匹配的文件，不全量加载

## 经验映射表

| 关键词 | 经验文件 | 加载时机 | 置信度 | 简述 |
|--------|----------|----------|--------|------|
| 方案设计, 技术选型, 版本号 | experience/方案设计流程/common-errors.md | 方案设计时 | 低 | 方案设计前必须先读 constitution.md 对齐技术栈版本 |
| MyBatis-Plus, 分页, Pagination, 雪花算法, ASSIGN_ID, IdType, ID策略 | experience/MyBatisPlus/common-errors.md | 实施前 | 低 | MP 3.5.x 分页需额外引入 mybatis-plus-jsqlparser；雪花算法迁移需同步四处（配置/注解/DDL/Jackson） |
| Apache POI, Word, docx, 文档解析 | experience/ApachePOI/common-errors.md | 实施前 | 低 | POI 异常不全是 IOException，需 catch Exception |
| Maven, 多模块, spring-boot:run, 启动 | experience/Maven多模块/common-errors.md | 实施前 | 低 | 多模块项目 run 前必须先 install |
| 代码审查, Controller, Mapper, Service, 分层, 接口, 单元测试, TDD, @Async, @Lazy, 自调用, 代理 | experience/代码审查模式/common-errors.md | 实施前 | 高 | Controller 禁止注入 Mapper；Service 拆接口+实现类；TDD 先写测试再写实现（第三次验证）；@Async 放独立 bean 且同 bean 自调用需 @Lazy self 注入代理 |
| 代码审查, 自检, 实施完成, 测试, TDD | experience/代码审查模式/self-check.md | 实施前 | 高 | TDD 强制/单测覆盖/重复逻辑/死代码/接口拆分同步测试/@Async 独立 bean |
| 实体类, Entity, @TableName, BaseEntity, 中文注释, COMMENT, 新建表 | experience/实体类规范/self-check.md | 实施前 | 低 | 实体必须继承 BaseEntity + 字段中文注释 + DB 列 COMMENT + @EqualsAndHashCode(callSuper=true) |
| highlight.js, WebSocket, 前端单例 | experience/代码审查模式/common-errors.md | 实施前 | 低 | 前端库初始化提取单例；WebSocket 切换时先断开旧连接 |
| 日志, logback, traceId, 脱敏, 多环境, profile | experience/日志基础设施/common-errors.md | 实施前 | 低 | 新项目 Phase 1 就要配 logback + traceId + 脱敏 + 多环境拆分 |
| 日志, Spring Boot, 初始化, 配置, application.yml | experience/日志基础设施/self-check.md | 实施前 | 低 | 日志自检：logback/traceId/脱敏/文件输出/ERROR独立/多环境拆分/密码占位 |
| Flyway, 数据库迁移, migration, baseline, 版本管理 | experience/Flyway数据库迁移/common-errors.md | 实施前 | 低 | Flyway 10.x 需 flyway-mysql 模块；已有库需 baseline；社区版无 undo |
| UI还原, 模仿页面, 前端布局, el-aside, sidebar, WorkbenchView, 面板切换, mode | experience/前端UI还原/self-check.md | 实施前 | 低 | screenshot+snapshot 配合分析；el-aside+collapse 侧边栏；改布局同步检查 router；WorkbenchView 新增面板时所有入口函数都要重置 mode |
| 文件操作, 文件同步, 磁盘写入, DB一致性, Service事务 | experience/Service设计模式/common-errors.md | 实施前 | 低 | 文件操作先于 DB，文件失败抛 BusinessException 阻止 DB 提交，避免不一致 |
| Docker, docker-compose, 部署, Dockerfile, Nginx | experience/DockerCompose部署/self-check.md | 实施前 | 低 | Maven 多阶段缓存层；Nginx try_files history 模式；服务间用服务名通信 |
| logback, logback-test.xml, conversionRule, 脱敏 | experience/DockerCompose部署/common-errors.md | 实施前 | 低 | logback-test.xml 是保留名不能用；ClassicConverter 不支持括号语法 |
| JDK, Maven, 版本不匹配, 编译失败, devsoft, 本地开发 | experience/本地开发环境/self-check.md | 实施前 | 中 | 版本不匹配时先查 ~/devsoft 目录，已有 JDK 11/21、Maven 3.9.11 |
| CC CLI, -p, 非交互模式, slash command, /skill, 命令解析 | requirements/skill-command-converter/process.txt | 方案设计时 | 中 | CC CLI -p（非交互）不经过命令解析器，不支持 /skill-name，走这条路行不通 |
| Maven, 增量编译, 没有更新, JAR, clean package | requirements/delete-project-cleanup/process.txt | 实施前 | 中 | Maven 增量编译未感知源码变化时 JAR 不更新，必须 mvn clean package 而非 mvn package |

<!-- 
示例条目：
| 商品发放, 奖品, 发奖 | experience/商品发放/common-errors.md | 需求分析时 | 高 | 钱包选择、库存锁定顺序 |
| Apollo, 配置中心 | experience/Apollo配置/field-specs.md | 方案设计时 | 中 | Apollo 配置格式和已知坑点 |
-->

## 置信度规则

| 级别 | 含义 | 升降条件 |
|------|------|----------|
| **低** | 首次记录，未经验证 | 新沉淀的经验默认为低 |
| **中** | 被验证过 1 次（第二次遇到同样问题时确认有效） | 低→中：再次遇到并确认有效 |
| **高** | 被验证过 2+ 次，或涉及资金/安全/数据一致性 | 中→高：第三次验证，或用户明确标记 |

- 置信度为"高"的经验可以考虑升级到 `context/team/` 或 `context/rules/` 成为正式规范
- 被证伪的经验：降级或标记删除，不要静默保留错误经验

## 沉淀规则

何时新增条目：
1. **AI 连续犯同一类错 2 次** → `experience/{场景}/common-errors.md`
2. **某类操作每次都要人工提醒** → `experience/{场景}/self-check.md`
3. **发现新的字段规范或格式要求** → `experience/{场景}/field-specs.md`
4. **尝试过但走不通的路** → `experience/{场景}/dead-ends.md`
5. **首次顺利完成但路径不显然**（本轮修改文件 ≥ 5 个，或方案有明显转折）→ `experience/{场景}/common-errors.md` 或 `self-check.md`
   > 不等 AI 犯错，成功路径本身就有价值。Stop hook 检测到复杂任务时会自动提醒。

**dead-ends 索引强制要求**：
- `dead-ends.md` 中每一条记录，**必须同步在本表加一行关键词映射**
- 否则下次会话无法命中，沉淀等于没有
- 关键词取"走不通的方案名"或"你当时会搜索的词"（如 `CC CLI -p, 非交互模式`）
