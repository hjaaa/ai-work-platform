# BaaS 核心 MVP 设计(ai-work-baas)

- 日期:2026-07-17
- 状态:v3,已按两轮评审意见修订(修订记录见文末)
- 范围:「MySQL 版 BaaS 平台」首个子项目(核心 MVP,定位内部 Alpha)的设计文档,同时记录三个子项目的总体开发顺序决策

## 1. 背景与目标

在 ai-work-platform 中开发一个对标 Supabase、但数据库使用 MySQL 的 BaaS(Backend as a Service)平台:开发者在控制台建项目、可视化建表后,平台自动提供数据 CRUD API 与终端用户认证能力,无需编写后端代码。

同一仓库后续还将开发一个面向 Claude Code / Codex 等智能体的插件市场,BaaS 平台对应的 MCP 插件将作为该市场的首个插件上架。

**服务对象**:先内部使用(内部 Alpha),架构上预留对外(多租户配额、更强隔离)的演进路径。

## 2. 总体规划与开发顺序(已确认)

三个子项目各自独立走「设计 → 计划 → 实现」流程,依赖关系与顺序:

```
① MySQL 版 BaaS 平台 ──── 提供管理 API ────┐
                                          ├──→ ③ BaaS 的 MCP 插件(依赖 ① 和 ②)
② 插件市场 ──────── 提供分发渠道 ──────────┘
```

确认顺序(BaaS 平台为主产品):

1. **BaaS 核心 MVP**(本文档)
2. **插件市场 MVP**:插件注册/版本/发布,兼容 Claude Code marketplace 协议(另行设计)
3. **MCP 插件**:封装 BaaS 管理 API(见 7.3),作为市场首个插件上架(另行设计)
4. **BaaS 增量模块**:Storage(有 common-oss 底子)→ Realtime(binlog)→ Functions(最重,最后评估)

## 3. 已确认的关键决策

| 决策点 | 结论 |
|---|---|
| 服务对象 | 先内部用(内部 Alpha),预留对外能力 |
| 项目隔离模型 | 同一 MySQL 实例内,每项目独立 database + 独立 Runtime 账号;将来可升级为独立实例 |
| MVP 边界 | 项目管理 + 建表 + 动态 CRUD API + 终端用户 Auth(含会话)+ 表级 ACL + 固定 owner 列策略 |
| 行级权限 | 固定「owner 列 = JWT sub」策略进 MVP;完整规则表达式引擎放二期 |
| 动态 API 实现路线 | 元数据驱动的运行时动态 API,不做代码生成 |
| 模块名 | `ai-work-baas` |
| 查询语法 | PostgREST-inspired 简化子集(见 7.1),**不承诺 supabase-js 兼容** |
| 终端用户注册 | MVP 仅邮箱 + 密码;不做邮箱验证,**注册即视为邮箱已确认**;不做密码找回 |
| API Key 形态 | opaque 的 publishable/secret key(哈希存储、可多个、可轮换),不采用 Supabase legacy 的 JWT 型 anon/service_role key |
| 身份模型 | 双层:开发者走平台现有 auth/upms;终端用户为每项目独立体系,互不相通 |
| 密钥加密基线 | BaaS 专用 AES-256-GCM 加密器,主密钥仅来自环境变量/部署 Secret;**不继承**仓库默认 Jasypt 配置(PBEWithMD5AndDES) |
| 设计文档位置 | `context/specs/` 入库跟踪 |

## 4. 总体架构

新增后端模块 `ai-work-baas`(与 upms 平级的 Spring Cloud 服务,同时挂入 boot 单体 profile);前端在 `ai-work-ui` 新增 BaaS 控制台页面组。不改变现有服务的职责边界。

```
ai-work-baas
├── 管理面 /studio/**(平台登录态 + upms 权限)
│   ├── 项目管理:创建/删除项目(状态机驱动,见第 9 节)
│   ├── 表管理:可视化建表/改表(DDL 串行 + 操作日志,见第 9 节)
│   ├── API Key 管理:创建/吊销/轮换(见 8.1)
│   └── 终端用户管理、对账触发
└── 数据面 /data/{projectRef}/**(自有鉴权:API Key + 终端用户 JWT,不走平台 OAuth)
    ├── /rest/v1/{table}  动态 CRUD(元数据校验 + 参数化 SQL)
    └── /auth/v1/*        终端用户注册/登录/刷新/注销
```

关键机制:

- **元数据双写**:表结构既存平台元数据库,又真实 DDL 到项目 database。表结构以项目库 `information_schema` 为对账基准;ACL、密钥、owner 列配置等操作意图**只存在于平台库,对账不能重建**(见 9.4)。
- **专用数据源注册表**:`ProjectDataSourceRegistry`(BaaS 自研,见第 10 节)。**不复用** `ai-work-common-datasource` 的 JdbcDynamicDataSourceProvider(启动时全量加载,无按需注册)及其 Header/Session 数据源解析链(数据面不能信任客户端 Header 选择数据源);projectRef 只从鉴权后的请求上下文推导。
- **鉴权分离**:管理面用平台账号;数据面完全独立,`/data/**` 在资源服务器侧放行,由 BaaS 自己的 ApiKeyAuthFilter 承接。

## 5. 部署形态与入口契约

Cloud 网关会剥掉外部路径第一段(AiWorkRequestGlobalFilter 重写 StripPrefix=1);Boot 形态无网关且有 `/admin` context-path。两种形态外部 URL **不承诺相同**,差异由 SDK/调用方的 base URL 配置吸收:

| | Cloud 微服务形态 | Boot 单体形态 |
|---|---|---|
| 外部数据面 | `http://gw:9999/baas/data/{ref}/rest/v1/...` | `http://host:9999/admin/data/{ref}/rest/v1/...` |
| 外部管理面 | `http://gw:9999/baas/studio/...` | `http://host:9999/admin/studio/...` |
| 服务内部路径 | `/data/{ref}/...`、`/studio/...` | 同左 |

**约定**:调用方配置 `base_url` = 外部前缀 + `/data/{projectRef}`;其后路径两种形态完全一致(`/rest/v1/{table}`、`/auth/v1/*`)。API 文档与后续 SDK/MCP 插件均以 base_url 相对路径描述。

鉴权边界:`/data/**` 加入资源服务器 ignore-urls(平台 OAuth 放行),由 ApiKeyAuthFilter 强制校验;`/studio/**` 走平台登录态 + upms 权限,与现有服务一致。

## 6. 数据模型

### 6.1 平台元数据库(新建 `ai_work_baas` 库,独立 SQL 脚本)

| 表 | 内容 |
|---|---|
| `baas_project` | `project_ref`(短标识,入 URL,**不作为授权凭据**)、`db_name`、**状态机字段**(见 9.1)、`owner_user_id`(平台用户,Studio 归属校验依据)、`allowed_origins`(JSON,CORS 白名单)、runtime 账号名及**加密**凭据。不存任何明文 key |
| `baas_jwt_key` | 项目 JWT 签名密钥:HS256 secret **加密存储**、`kid`、状态(current/previous/revoked)、previous 带 `valid_until`(= 轮换时刻 + access TTL)。存在未过期 previous 时禁止再次轮换 |
| `baas_api_key` | API Key:project_id、类型(publishable/secret)、**key 哈希**、明文前缀(展示用,如 `pub_a1b2…`)、状态、创建/吊销/最后使用时间。支持同类型多 key 无停机轮换 |
| `baas_table` | 表元数据:project_id、table_name、注释、状态、`owner_column`(可空,行归属列名,见 8.3) |
| `baas_column` | 列元数据:类型、长度、可空、默认值、主键/自增/唯一/单列索引、注释 |
| `baas_table_acl` | 表级权限:每表 × {anon, authenticated} × {select, insert, update, delete} 开关。**新表默认全关** |
| `baas_ddl_log` | Schema 操作日志:操作 ID(幂等键)、project_id、DDL 内容、步骤、状态、错误信息、重试次数 |
| `baas_audit_log` | 敏感操作审计:key 创建/吊销、JWT 密钥轮换、项目删除等 |

### 6.2 项目 database 内系统表(建项目时初始化,`_` 前缀,数据 API 不可见)

| 表 | 内容 |
|---|---|
| `_users` | 终端用户:id(**固定 bigint 自增**,owner 列类型与其对齐)、email(trim + 小写规范化后唯一)、password_hash(bcrypt)、raw_meta(JSON)、时间戳 |
| `_sessions` | 会话:id、user_id、创建/最后活跃时间、状态 |
| `_refresh_tokens` | refresh token:**哈希存储**、session_id、是否已用(一次性轮换)、过期时间 |

## 7. API 契约

### 7.1 数据 API(PostgREST-inspired 简化子集)

```
GET    /rest/v1/{table}?status=eq.active&age=gte.18
       &select=id,name&order=created_at.desc&limit=20&offset=0
POST   /rest/v1/{table}          单条或数组批量插入
PATCH  /rest/v1/{table}?id=eq.1  按条件更新(无过滤条件则 400 拒绝)
DELETE /rest/v1/{table}?id=eq.1  按条件删除(无过滤条件则 400 拒绝)
```

语义细则(MVP 契约,实现不得自行发挥):

- 操作符:`eq / neq / gt / gte / lt / lte / like / in / is`;`in` 语法 `col=in.(a,b,c)`;`is` 仅支持 `is.null / is.not_null`
- 多个过滤条件之间为 **AND**;OR 不进 MVP
- 列名对照元数据白名单校验,非法列 400;值全部参数化绑定
- 状态码:GET 200(**零匹配返回 `[]`,不是 404**;表不存在才 404);POST 201;PATCH/DELETE 200 + 受影响行数,匹配零行返回 200 + 0
- POST 数组批量插入为**单事务原子**:任一行失败整体回滚
- `Prefer: return=representation` 时 POST/PATCH 返回行数据,默认仅返回主键/行数
- 分页:默认 `limit=100`,最大 `1000`;`Prefer: count=exact` 时响应头返回 `Content-Range: 0-19/87` 总数
- 关联/嵌套查询、OR、聚合不进 MVP

### 7.2 Auth API(终端用户,完整会话模型)

```
POST /auth/v1/signup                          邮箱+密码注册(即视为邮箱已确认)
POST /auth/v1/token?grant_type=password       登录:签发 access JWT + refresh token,落 _sessions
POST /auth/v1/token?grant_type=refresh_token  刷新:refresh token 一次性轮换,旧 token 复用即撤销整个会话
POST /auth/v1/logout                          注销:撤销当前会话及其 refresh token
GET  /auth/v1/user                            凭 access JWT 取当前用户
PUT  /auth/v1/user/password                   修改密码:成功后撤销该用户全部会话
```

- **access JWT**:HS256(项目当前 `baas_jwt_key` 签名,带 `kid`),TTL 1 小时,claims 固定为 `iss=baas/{project_ref}`、`aud={project_ref}`、`sub={user_id}`、`role=authenticated`、`session_id`、`iat`、`exp`。验签接受 current 及未过 `valid_until` 的 previous kid
- **refresh token**:不透明随机串,哈希落 `_refresh_tokens`,TTL 30 天,一次性使用、每次刷新轮换
- **并发刷新语义**:刷新在事务内对 token 行加锁;设 10 秒 reuse grace——窗口内重复使用同一旧 token 返回同一个新 token(幂等,容忍多标签/网络重试),**超窗复用才判定为泄露并撤销整个会话**
- 会话撤销以 refresh token 为准;access JWT 短 TTL 自然过期,MVP 不做 access token 黑名单
- **注册/登录细则**:邮箱 trim + 小写规范化;密码长度 8–72 字节(bcrypt 只取前 72 字节,超长直接 400 拒绝,避免截断歧义);bcrypt cost 10
- 邮箱验证、密码找回、OAuth 第三方登录均不进 MVP(见第 15 节)

### 7.3 管理面 API(Studio 契约,MCP 插件的依赖面)

```
/studio/projects                     GET 列表 / POST 创建 / DELETE 删除(状态机驱动)
/studio/projects/{ref}               GET 详情(含状态)
/studio/projects/{ref}/tables        GET / POST / PATCH / DELETE(建改删表,附操作 ID 幂等)
/studio/projects/{ref}/tables/{t}/acl    GET / PUT 表级 ACL 与 owner_column 配置
/studio/projects/{ref}/keys          GET / POST 创建 / POST {id}/revoke 吊销
/studio/projects/{ref}/jwt-keys      POST rotate 轮换签名密钥
/studio/projects/{ref}/users         GET / DELETE 终端用户管理
/studio/projects/{ref}/reconcile     POST 触发表结构对账
```

管理面沿用平台 `R<T>` 响应与 springdoc 文档;数据面提供**静态** OpenAPI(描述查询语法契约,不做 per-project 动态反射)。项目 CORS 白名单(`allowed_origins`)通过 `PATCH /studio/projects/{ref}` 配置。

**项目级对象授权(防 IDOR)**:upms 菜单/API 权限只解决「能否进入 Studio 功能」,不能替代项目归属检查——

- 项目列表仅返回 `owner_user_id = 当前平台用户` 的项目
- 所有 `/studio/projects/{ref}/**` 操作(详情、表、Key、用户、删除、对账)一律校验项目归属,不匹配返回 404(不泄露存在性)
- 仅持有专门 upms 角色(如 `ROLE_BAAS_ADMIN`)的超级管理员可跨项目操作
- `project_ref` 只是路由标识,**不作为授权凭据**

### 7.4 请求身份三态

数据面请求头带 `apikey`(publishable key 或 secret key,opaque,服务端查 `baas_api_key` 哈希解析出项目与基础角色),终端用户再叠加 `Authorization: Bearer <access JWT>`:

- `anon`:仅 publishable key,受表级 ACL 约束
- `authenticated`:publishable key + 有效 JWT,受表级 ACL + owner 列策略约束
- `service_role`:secret key,绕过 ACL 与 owner 策略,仅服务端持有,Studio 默认遮挡显示

**三方项目一致性(强制)**:URL 中的 `{projectRef}`、API Key 所属项目、JWT 的 `iss/aud` 三者必须指向同一项目,**任一不一致一律 401**;一致性校验完成之前不选择数据源、不建立任何项目库连接。校验顺序:apikey 解析项目 → 与 URL projectRef 比对 → 若带 JWT 再校验 iss/aud 与 kid。

**secret key 与 JWT 互斥**:secret key 恒定解析为 `service_role`;secret key 请求**同时携带终端用户 JWT 直接 401 拒绝**,不做身份混合。

## 8. 权限模型

### 8.1 API Key

opaque key(如 `pub_` / `sec_` 前缀 + 随机串),创建时仅展示一次明文,库中只存哈希与展示前缀;每项目每类型可同时存在多个有效 key,轮换流程 = 创建新 key → 切换调用方 → 吊销旧 key,无停机。JWT 签名密钥与 API Key 完全分离(6.1)。

### 8.2 表级 ACL

`baas_table_acl` 控制 anon / authenticated 对每表的 select/insert/update/delete;**新表默认全部关闭**,需显式开启。

### 8.3 固定 owner 列策略(MVP 行级权限)

每表可选配置一个 `owner_column`(必须是该表已有列,**类型必须为 bigint**、与 `_users.id` 一致;配置时校验类型并**强制建立单列索引**)。开启后按角色的完整读写规则:

| 角色 | SELECT | INSERT | UPDATE/DELETE |
|---|---|---|---|
| `authenticated` | 自动追加 `AND owner = jwt.sub` | owner 由服务端**强制写入 `jwt.sub`**(请求体含该列即 400) | 过滤条件自动追加 `AND owner = jwt.sub`;**请求体包含 owner 列即 400**(禁止转移归属) |
| `anon` | 受 ACL 约束,无行过滤 | **请求体包含 owner 列即 400**,owner 落 NULL | 受 ACL 约束,无行过滤 |
| `service_role` | 全量 | 唯一允许显式指定 owner 的角色 | 唯一允许修改 owner 的角色 |

未配置 owner_column 的表 = authenticated 可访问全表(受 ACL 开关约束),Studio 界面须明确提示这一语义。完整规则表达式引擎(对标 RLS policy)放二期。

## 9. 项目与 Schema 生命周期

### 9.1 项目状态机

```
PROVISIONING → ACTIVE → DELETING → DELETED
      ↓
    FAILED(可重试 provisioning 或清理)
```

创建流程(Provisioner 账号执行):建 database → 建 runtime 账号并仅授权本库 DML → 初始化系统表 → 写元数据 → ACTIVE。任一步失败置 FAILED 并记录步骤,支持幂等重试;非 ACTIVE 项目数据面一律 403。

### 9.2 DDL 操作

- 同项目 DDL **串行**:Redis 分布式锁,锁内执行「校验 → DDL → 写元数据」
- 每次操作携带客户端操作 ID,落 `baas_ddl_log`,重复提交幂等返回原结果
- MySQL DDL 不可回滚,失败时记录已执行步骤,依赖对账恢复一致性

### 9.3 删除流程

删项目:置 DELETING(数据面即刻阻断)→ 吊销全部 API Key → 关闭连接池 → 软删元数据 → 延迟 N 天(默认 7)物理 DROP DATABASE 与账号。删表同理(阻断 → 软删 → 延迟 DROP)。**延迟 DROP 期间同名对象为 tombstone 状态,禁止重建同名项目 ref / 同名表**,物理清理完成后方可复用名称。

### 9.4 对账

手动触发(`/reconcile`)+ 可选定时。**范围仅表结构**:以项目库 `information_schema` 为准修正 `baas_table`/`baas_column`;ACL、owner 配置、密钥属于操作意图,以平台库为唯一事实源,不参与对账。结构冲突(如列类型不一致)标记该表为 CONFLICT 状态并阻断其数据 API,人工处置。**对账跳过非 ACTIVE 项目及软删/tombstone 表——物理表仍存在不构成「复活」软删除对象的依据**。

## 10. 数据源与数据库账号

### 10.1 账号分层

| 账号 | 权限 | 用途 |
|---|---|---|
| Provisioner | CREATE DATABASE / CREATE USER / GRANT / 全库 DDL | 仅管理面生命周期操作 |
| 项目 Runtime(每项目一个) | 仅本项目库 DML(SELECT/INSERT/UPDATE/DELETE) | 数据面全部请求 |

所有数据库凭据按 §12 加密基线加密落库。**隔离能力如实声明**:同实例独立 database + 独立账号提供的是命名空间与权限隔离,不提供资源、故障或服务进程被攻破后的隔离;后者留待独立实例形态。

### 10.2 ProjectDataSourceRegistry(BaaS 专用)

- 按 projectRef 懒加载创建连接池,**单飞**(并发首次请求只建一次)
- LRU 淘汰控制活跃池数量上限;**有活跃请求的池禁止淘汰**;淘汰与项目删除时优雅关闭连接池
- 每池 max 连接数 + 全服务总连接数预算,双层限制
- projectRef 仅来源于鉴权后的请求上下文,与 dynamic-datasource 的 Header/Session/参数解析链完全隔离

## 11. 错误处理

- 数据面用 HTTP 语义 + PostgREST 风格错误体 `{code, message, details, hint}`,不复用平台 `R<T>`
- 映射:唯一键冲突→409,表不存在→404,列名/语法非法→400,ACL 或 owner 策略拒绝→403,apikey 无效/JWT 无效→401,项目非 ACTIVE→403,超资源限制→413/429
- 原始 SQL 错误不透传(防泄露表结构),服务端做**结构化脱敏记录**(SQL 参数值、邮箱等业务字段值同样属于敏感信息,不得原样落日志)

## 12. 安全

### 12.1 密钥加密基线(不继承仓库默认 Jasypt)

仓库现有 Jasypt 基线不满足 BaaS 密钥的保护要求:默认算法为 `PBEWithMD5AndDES` + NoIvGenerator(common-config.yml),且 dev 配置中 Jasypt 根密码 `aiwork` 明文提交在 Git 中(application-dev.yml)。因此:

- BaaS 的 JWT secret、数据库凭据、API Key 哈希盐等一律使用 **BaaS 专用加密器**,算法 AES-256-GCM(随机 IV)
- 主密钥只能来自**环境变量或部署 Secret**,不得入库、入 Nacos 配置中心或提交 Git;未配置主密钥时 BaaS 模块启动失败(fail-fast),不回退到默认 Jasypt
- 密文记录带加密版本前缀(如 `v1:`),预留主密钥轮换(新版本密钥写入用新前缀,读取按前缀路由)

### 12.2 通用防线

- 注入防线:表名/列名对照元数据白名单 + 值全部 PreparedStatement 参数绑定;DDL 层标识符限定 `^[a-z][a-z0-9_]{0,63}$` 并过滤 MySQL 保留字
- `_` 前缀系统表对数据 API 完全不可见
- CORS:数据面支持 per-project 允许来源配置,MVP 默认 `*` 可收紧;正确响应 OPTIONS 预检
- Auth 防暴力:基于 Redis 的登录/注册失败计数限速(每邮箱 + 每 IP)
- 日志脱敏:password、key、JWT 不落日志;DDL 与密钥操作入 `baas_audit_log`
- 限流:gateway/sentinel 基础 QPS 阈值 + 服务内资源限制(第 13 节)兜底慢查询

## 13. 资源限制(MVP 默认值,可配)

| 项 | 限制 |
|---|---|
| 请求体大小 | 1 MB |
| 批量插入行数 | 1000 行 |
| 过滤条件数量 | 每请求 20 个 |
| SQL 执行超时 | 5 秒(JDBC queryTimeout) |
| 单项目连接池 | max 10;全服务总连接预算另设上限 |

### 表编辑器能力边界

- 类型白名单:`bigint / int / decimal(p,s) / varchar(n≤4096) / text / boolean / date / datetime / json`
- 默认值:常量或 `CURRENT_TIMESTAMP`
- 约束:主键固定为自增 `id bigint`(建表自动生成);单列唯一、单列索引
- **不支持**:复合主键/复合索引/复合唯一、外键、生成列(`baas_column` 模型即按此约束设计)

## 14. 测试策略

- **单元测试**(回归主防线):查询语法解析器、SQL 构建器、角色/ACL/owner 策略解析、JWT 与 key 校验
- **集成测试**:Testcontainers 真 MySQL,覆盖「建项目状态机 → 建表 → CRUD 各操作符与语义细则 → signup/login/refresh/logout → ACL 与 owner 策略拦截 → key 吊销生效」全链路
- **安全场景必测**:owner 伪造/转移(各角色 × INSERT/PATCH 携带 owner 列)、URL/apikey/JWT 三项目标识不一致、secret key 携带 JWT、Studio 跨项目越权(IDOR,替换 `{ref}`)、并发 refresh(grace 窗口内/外)、连续 JWT 轮换、软删除后对账不复活、Cloud 与 Boot 两种形态的鉴权链各自生效
- **交付物**:数据面静态 OpenAPI + 管理面 springdoc 文档,后续 MCP 插件以 7.3 为契约

## 15. 明确不进 MVP 的范围

- 完整行级规则表达式引擎(对标 RLS policy,二期)
- 关联/嵌套查询、OR 过滤、聚合
- 邮箱验证、密码找回、手机号注册、OAuth 第三方登录、Magic Link
- supabase-js 兼容、per-project 动态 OpenAPI 反射、TS 类型生成
- Storage、Realtime、Edge Functions(按第 2 节顺序后置)
- 备份/恢复、SQL Editor、直连数据库、视图/触发器/存储过程、数据导入导出、团队协作
- 多租户资源配额与计量计费(对外时再做)
- 每项目独立 MySQL 实例(预留演进)

## 16. 修订记录

- **v3(2026-07-17)**:按复审意见修订,4 项安全 P0——① owner 策略补全写路径:角色 × 操作完整规则表(anon/authenticated 携带 owner 列一律 400,仅 service_role 可指定/修改 owner),owner 列强制 bigint + 单列索引(§8.3);② URL/apikey/JWT 三方项目一致性强制校验,不一致 401,校验通过前不选择数据源;secret key 与终端用户 JWT 互斥(§7.4);③ Studio 项目级对象授权:owner_user_id 归属校验、列表过滤、ROLE_BAAS_ADMIN 跨项目、project_ref 不作为授权凭据(§7.3);④ 密钥加密基线:BaaS 专用 AES-256-GCM 加密器,主密钥仅环境变量/Secret,fail-fast,密文带版本前缀,明确不继承默认 Jasypt(§12.1)。P1——refresh 并发 grace 窗口与事务行锁(§7.2)、JWT previous key valid_until 与轮换限制(§6.1)、对账跳过 tombstone/延迟 DROP 期间禁止重建同名(§9.3/9.4)、allowed_origins 数据模型与配置接口(§6.1/§7.3)、邮箱规范化与 bcrypt 长度边界(§7.2)、日志改结构化脱敏(§11)、安全场景测试清单(§14)。
- **v2(2026-07-17)**:按评审意见修订——① owner 列策略进 MVP(8.3);② 补完整会话模型:_sessions/_refresh_tokens、refresh 轮换、logout、改密撤销、JWT claims 约束(7.2);③ API Key 改 opaque publishable/secret + baas_api_key 哈希多 key 轮换,JWT 签名密钥独立并支持 kid 双版本(6.1、8.1);④ 补项目/DDL 状态机、操作日志、串行锁、幂等、删除阻断流程、对账边界(第 9 节);⑤ 拆分 Provisioner/Runtime 账号、凭据加密、隔离能力如实声明、专用 ProjectDataSourceRegistry(第 10 节);⑥ 明确 Cloud/Boot 双形态入口契约与 base_url 约定(第 5 节);⑦ P1:REST 语义细则、资源限制、表编辑器能力边界、管理面 API 契约、CORS/防暴力/审计、范围外清单扩充。
- **v1(2026-07-17)**:初稿,逐节确认通过。
