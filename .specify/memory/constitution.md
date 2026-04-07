<!--
  Sync Impact Report
  ==================
  Version change: 1.0.0 → 1.0.1 (PATCH — tech stack version refinement)

  Modified sections:
    - 技术栈约束: Java 17+ → Java 21+, Spring Boot 3.x → Spring Boot 3.5.x

  Added principles: none
  Removed principles: none
  Added sections: none
  Removed sections: none

  Templates requiring updates:
    ✅ plan-template.md — no version-specific references, no update needed
    ✅ spec-template.md — no structural conflict
    ✅ tasks-template.md — no impact

  Follow-up TODOs: none
-->

# AI Work Platform Constitution

## Core Principles

### I. 分层架构优先（MUST）

**Rules**:
- Controller 层只做：参数校验（javax/jakarta validation）、鉴权判断、调用 Service、封装统一响应体。禁止在 Controller 中出现业务判断、数据转换或直接访问 Repository/Mapper
- Service 层承担业务编排与事务管理。Service 之间允许互调，但禁止循环依赖。Service 禁止直接操作 HTTP 请求/响应对象
- Repository/Mapper 层只负责数据访问。禁止在 Mapper XML 或 SQL 中嵌入业务逻辑（条件分支、金额计算等），纯查询优化除外
- DTO（接口入参/出参）、VO（视图对象）、DO/Entity（持久化对象）严格分离。跨层传输 MUST 通过显式转换（手写或 MapStruct），禁止直接透传 Entity 到 Controller 响应

**Rationale**: 分层边界不清是企业后端腐化的首要原因。Controller 膨胀、Service 贫血或 Mapper 藏逻辑都会导致不可测试、不可替换的耦合结构

**Implications**:
- Plan 阶段 MUST 明确每个功能涉及的分层组件及其职责边界
- Tasks 阶段 MUST 按 Controller → Service → Mapper/Repository 顺序或反向拆解，禁止一个 Task 跨越多层
- 代码审查 MUST 检查是否有业务逻辑泄漏到 Controller 或 Mapper

### II. 构造器注入与显式依赖（MUST）

**Rules**:
- 所有 Spring Bean 的依赖注入 MUST 使用构造器注入。禁止 `@Autowired` 字段注入和 setter 注入
- 构造器参数 MUST 声明为 `private final`，确保不可变
- 禁止使用 `@Autowired` 注解（包括构造器上的冗余标注，单构造器场景 Spring 自动识别）
- 如果一个类的构造器参数超过 7 个，MUST 重新审视职责划分，大概率违反单一职责

**Rationale**: 字段注入隐藏了真实依赖关系，使得类可以在不完整状态下被构造，单元测试时无法通过编译器发现缺失的 mock。构造器注入让依赖图在编译期可见

**Implications**:
- 所有新建 Service/Component 类 MUST 遵循此规则
- 遗留代码的字段注入在被修改时 MUST 顺带迁移为构造器注入
- 单元测试中直接通过 `new XxxService(mockA, mockB)` 构造被测对象，不依赖 Spring 容器

### III. 可测试性作为交付门禁（MUST）

**Rules**:
- 核心业务逻辑（Service 层）MUST 有单元测试覆盖：正常路径 + 至少 1 个边界条件 + 至少 1 个异常路径
- 单元测试中外部依赖（DB、Redis、HTTP、MQ）MUST mock（Mockito），禁止在单元测试中启动 Spring 容器或真实中间件
- Controller 层 MUST 有以下场景的测试：参数校验失败（400）、鉴权失败（401/403）、正常成功响应结构
- 测试方法命名 MUST 使用 `should_xxx_when_yyy` 或 `given_yyy_when_xxx_then_zzz` 格式
- 测试 MUST 能通过 `mvn test` 或 `gradle test` 独立运行，不依赖外部环境（数据库、Redis、MQ 等）

**Rationale**: 没有测试的代码是不可信的代码。测试不仅验证正确性，更是强制设计合理性的手段——难以测试的代码几乎必然存在设计问题

**Implications**:
- Tasks 阶段每个 Service 实现任务 MUST 伴随一个对应的测试任务
- `/speckit-implement` 执行时，先写测试、确认失败，再写实现（Red-Green-Refactor）
- 功能交付前 MUST 执行完整测试套件并全部通过

### IV. API / 数据兼容性默认优先（MUST）

**Rules**:
- 对外 REST API 变更 MUST 向后兼容：新增字段设为可选（nullable/有默认值）、禁止删除或重命名已发布字段、禁止改变已有字段的语义
- 若必须做破坏性变更，MUST 提供版本化方案（URL 路径版本 `/v2/xxx` 或 Header 版本），并在 Plan 中明确迁移策略和过渡期
- 数据库 schema 变更 MUST 向前兼容：新增列 MUST 有默认值或允许 NULL、禁止直接删除列（先标记废弃，下个版本再删）、禁止修改列类型导致数据截断
- MQ 消息体变更 MUST 遵循相同兼容规则：消费者 MUST 能容忍未知字段（不报错），生产者新增字段不影响旧消费者

**Rationale**: 企业系统的调用方往往不受你控制（前端、第三方、其他团队的服务）。一次不兼容变更可能导致级联故障，且回滚成本极高

**Implications**:
- Spec 阶段涉及接口或数据模型变更时 MUST 标注兼容性影响
- Plan 阶段 MUST 包含迁移策略（如有破坏性变更）
- 代码审查 MUST 检查 API 和 schema 变更的兼容性

### V. 可观测性内建（MUST）

**Rules**:
- INFO 级别日志 MUST 覆盖所有关键业务节点：请求入口（含主要参数摘要）、状态变更、外部调用（RPC/HTTP/MQ 发送）、业务异常分支
- 每条业务日志 MUST 包含可追踪的上下文：业务主键（orderId/userId 等）+ requestId/traceId。推荐通过 MDC 统一注入 traceId
- WARN 用于可恢复异常（重试、降级、入参不合法）；ERROR 仅用于不可恢复异常（数据不一致、外部依赖不可用）。禁止滥用 ERROR
- 禁止日志打印敏感信息：密码、token、身份证、银行卡、完整手机号。手机号脱敏为 `138****1234`，身份证脱敏为 `3201****1234`
- 大对象禁止直接 `toString()` 写入日志。包含列表或嵌套结构的对象 MUST 只记录关键标识和 size

**Rationale**: 线上问题排查 90% 靠日志。日志不足导致"盲飞"，日志过多导致成本爆炸和敏感泄露。合理的分级和脱敏是可观测性的基础

**Implications**:
- Tasks 阶段每个 Service 实现任务 MUST 包含日志埋点
- Plan 阶段 MUST 规划统一的 traceId 传递方案
- 代码审查 MUST 检查敏感信息是否脱敏

### VI. 简单性优先于抽象（MUST）

**Rules**:
- 禁止为仅使用一次的逻辑创建 Helper/Util 类或抽象接口。三处以上重复才考虑抽取
- 禁止引入新框架或重量级依赖（如新的 ORM、序列化库、工具包），除非在 Plan 中提供：引入原因、替代方案对比、体积/维护/license 风险评估
- 方法体 MUST ≤ 60 行（空行和注释不计）。超出必须拆分为语义清晰的私有方法
- 禁止提前设计"未来可能需要"的扩展点（Strategy/Factory/Plugin 等）。当前不需要就不建。需要时再重构
- 配置项 MUST 只暴露当前确实需要外部控制的参数。禁止"先配置化以防万一"

**Rationale**: 过早抽象比重复代码危害更大——它固化了错误的假设，增加了理解成本，且往往在真正需要扩展时方向不对。简单直接的代码才是最容易改的代码

**Implications**:
- Plan 阶段的 Complexity Tracking 表 MUST 记录所有超出"直接实现"的设计决策及理由
- 代码审查 MUST 挑战所有新建的抽象层："当前有几个实现？去掉这层抽象会怎样？"
- Tasks 中禁止出现"创建 xxx 工厂/策略/基类"类型的任务，除非 Plan 中已论证必要性

## 技术栈约束

- **语言/版本**: Java 21+（具体版本以项目 pom.xml 为准）
- **框架**: Spring Boot 3.5.x
- **ORM**: MyBatis-Plus 或 Spring Data JPA（以项目既有选择为准，禁止混用）
- **缓存**: Redis（通过 Spring Data Redis 或 Redisson）
- **消息队列**: 以项目既有选择为准（RocketMQ/Kafka/RabbitMQ）
- **构建工具**: Maven 或 Gradle（以项目既有为准）
- **新增依赖**: MUST 遵循原则 VI 的评估流程

## 开发流程与质量门禁

- 异常处理：禁止吞异常。catch 后 MUST 重新抛出业务异常或记录日志并返回明确错误。统一使用项目既有异常体系（如 BusinessException）
- 对外接口返回：禁止暴露堆栈信息。错误信息对用户友好，对排查有足够上下文
- 幂等设计：默认假设接口会被重复调用、消息会重复投递。幂等策略优先级：唯一约束 > 幂等表/幂等 key > 分布式锁
- 状态流转：MUST 显式校验 from → to，禁止直接 set 目标状态
- 金额处理：MUST 使用 BigDecimal，明确 scale 和 RoundingMode
- SQL 规范：禁止 `select *`，禁止字符串拼接 SQL（MUST 参数化），大表分页禁止深 offset（MUST 使用游标/ID 方案）
- 鉴权变更：MUST 明确角色/权限点和资源范围（tenant/org/user）

## Governance

- 本 Constitution 优先级高于所有其他项目文档和实践。出现冲突时以 Constitution 为准
- 修改原则 MUST 提供：变更原因、影响范围分析、迁移方案（如影响已有代码）
- 版本策略：MAJOR（原则删除或语义重定义）、MINOR（新增原则或实质性扩展）、PATCH（措辞优化、笔误修正）
- 所有代码提交 MUST 自检是否符合 Constitution 各项原则
- Constitution Check 不通过的 Plan MUST NOT 进入 Tasks 阶段

**Version**: 1.0.1 | **Ratified**: 2026-04-03 | **Last Amended**: 2026-04-03
