# BaaS 核心 MVP 设计(ai-work-baas)

- 日期:2026-07-17
- 状态:v21;v1~v5 经四轮整体评审定稿,v6 附实施规划与会话交接(§16),v8 补齐 Plan B(表管理与 DDL)设计细化,v9~v21 按 Plan B 十三轮设计评审修订
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
| `baas_project` | `project_ref`(短标识,入 URL,**不作为授权凭据**)、`db_name`、**状态机字段**(见 9.1)、`owner_user_id`(平台用户,Studio 归属校验依据)、`allowed_origins`(JSON,CORS 白名单)、runtime 账号名及**加密**凭据、**`ddl_fence_epoch`(BIGINT NOT NULL DEFAULT 0,项目级单调 fencing 计数,见 9.2;Plan B 迁移新增并按 DEFAULT 0 回填存量项目——Plan A 的 `createProject()` 不设置该列,靠默认值保证现有建项目路径无需改造即可插入)**。不存任何明文 key |
| `baas_jwt_key` | 项目 JWT 签名密钥:HS256 secret **加密存储**、`kid`、状态(current/previous/revoked)、previous 带 `valid_until`(= 轮换时刻 + access TTL)。**常规轮换**在存在未过期 previous 时拒绝;**紧急轮换**(current key 疑似泄露)为独立状态转换:撤销**全部 current 与 previous** → 直接生成新 current,**不保留 previous**。所有旧 access JWT 立即失效(预期代价);`_sessions` 与 refresh token 不撤销,合法客户端凭 refresh token 换取新 key 签发的 access JWT;操作记高等级审计日志 |
| `baas_api_key` | API Key:project_id、类型(publishable/secret)、**key 哈希**、明文前缀(展示用,如 `pub_a1b2…`)、状态、创建/吊销/最后使用时间。支持同类型多 key 无停机轮换 |
| `baas_table` | 表元数据:project_id、table_name、注释、状态、`owner_column`(可空,行归属列名,见 8.3) |
| `baas_column` | 列元数据:类型、长度、可空、默认值、主键/自增/唯一/单列索引、注释 |
| `baas_table_acl` | 表级权限:每表 × {anon, authenticated} × {select, insert, update, delete} 开关。**新表默认全关** |
| `baas_ddl_log` | Schema 操作日志:project_id + 操作 ID 联合唯一(幂等键)、操作类型(create/alter/drop/acl-config/cleanup-drop/**reconcile**)、目标表名与**不可变目标表 ID**(`baas_table.id`;cleanup-drop 一律以表 ID 定位并锁内重读校验,防止表名释放后同名新表被陈旧任务误删,见 9.2;**项目级对账两者均为 NULL**)、**request_hash**(操作指纹,见 9.2)、**result_snapshot**(成功结果 JSON,幂等重放返回)、**owner_token**(本次执行唯一标识,兼作 Redis 锁 value、日志条件更新的 fencing 键与陈旧 RUNNING 接管依据,见 9.2)、**fence_epoch**(BIGINT NULL:历史记录与 PENDING cleanup 为 NULL,**转 RUNNING 取得所有权时必须赋值**,见 9.2)、**trigger_source**(VARCHAR(16) NULL:非 reconcile 操作为 NULL,对账为 MANUAL/SCHEDULED,见 9.4)、DDL 内容(**默认值字面量以占位符脱敏记录**)、检查点步骤(PREPARED/DDL_APPLIED/METADATA_APPLIED,见 9.2)、状态(**PENDING**/RUNNING/SUCCESS/FAILED;PENDING 仅用于预建的 cleanup-drop 待调度记录,该状态下 `owner_token` 为 NULL,见 9.2)、错误信息、重试次数。**Plan B 需附 ALTER 迁移脚本**:本表补新增字段;同时扩容 Plan A 建的 `baas_table.comment` → varchar(2048)、`baas_column.comment` → varchar(1024)、`baas_column.default_value` → text(现均为 varchar(255),小于 §7.3 API 允许上限,会导致项目库 DDL 成功后平台元数据写入失败) |
| `baas_audit_log` | 敏感操作审计:key 创建/吊销、JWT 密钥轮换、项目删除等 |

### 6.2 项目 database 内系统表(建项目时初始化,`_` 前缀,数据 API 不可见)

| 表 | 内容 |
|---|---|
| `_users` | 终端用户:id(**固定 bigint 自增**,owner 列类型与其对齐)、email(trim + 小写规范化后唯一)、password_hash(bcrypt)、raw_meta(JSON)、时间戳 |
| `_sessions` | 会话:id、user_id、创建/最后活跃时间、状态 |
| `_refresh_tokens` | refresh token:**哈希存储**、session_id、过期时间、`consumed_at`、`replacement_token_id`、`reuse_grace_until`、`replay_payload_ciphertext`(AES-GCM 加密的首次刷新完整响应,仅存活于 grace 窗口,见 7.2) |

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
POST /auth/v1/token?grant_type=refresh_token  刷新:refresh token 一次性轮换,超出 reuse grace 后复用才撤销整个会话
POST /auth/v1/logout                          注销:撤销当前会话及其 refresh token
GET  /auth/v1/user                            凭 access JWT 取当前用户
PUT  /auth/v1/user/password                   修改密码:成功后撤销该用户全部会话
```

- **access JWT**:HS256(项目当前 `baas_jwt_key` 签名,带 `kid`),TTL 1 小时,claims 固定为 `iss=baas/{project_ref}`、`aud={project_ref}`、`sub={user_id}`(**bigint 用户 ID 的十进制字符串**,验签后严格解析为 bigint 再绑定 SQL,解析失败 401)、`role=authenticated`、`session_id`、`iat`、`exp`。验签接受 current 及未过 `valid_until` 的 previous kid
- **refresh token**:不透明随机串,哈希落 `_refresh_tokens`,TTL 30 天,一次性使用、每次刷新轮换
- **并发刷新语义**(同事务数据库方案):首次刷新在**行锁事务**内完成——创建子 token、父 token 标记 `consumed_at` 并写入 `replacement_token_id` 与 `reuse_grace_until`(+10 秒),同时把完整刷新响应经 AES-GCM 加密存入 `replay_payload_ciphertext`(AAD 绑定 `project_id + session_id + token_id`,防密文跨记录替换)。grace 窗口内重放同一旧 token:解密并返回**同一响应**(幂等,容忍多标签/网络重试);**超窗重放判定为泄露,撤销整个会话**;grace 结束后清除密文(惰性 + 定时)
- 会话撤销以 refresh token 为准;access JWT 短 TTL 自然过期,MVP 不做 access token 黑名单
- **注册/登录细则**:邮箱 trim + 小写规范化;密码长度 8–72 字节(bcrypt 只取前 72 字节,超长直接 400 拒绝,避免截断歧义);bcrypt cost 10
- 邮箱验证、密码找回、OAuth 第三方登录均不进 MVP(见第 15 节)

### 7.3 管理面 API(Studio 契约,MCP 插件的依赖面)

```
/studio/projects                     GET 列表 / POST 创建
/studio/projects/{ref}               GET 详情(含状态) / PATCH 更新(含 allowed_origins) / DELETE 删除(状态机驱动)
/studio/projects/{ref}/tables              GET 列表 / POST 建表(附操作 ID 幂等)
/studio/projects/{ref}/tables/{table}      GET 详情 / PATCH 改表 / DELETE 删表(附操作 ID 幂等)
/studio/projects/{ref}/tables/{table}/acl  GET / PUT 表级 ACL 与 owner_column 配置
/studio/projects/{ref}/keys                GET / POST 创建 / POST {id}/revoke 吊销
/studio/projects/{ref}/jwt-keys            POST rotate 常规轮换 / POST emergency-rotate 紧急轮换
/studio/projects/{ref}/users               GET 终端用户列表
/studio/projects/{ref}/users/{userId}      DELETE 删除终端用户
/studio/projects/{ref}/reconcile           POST 触发表结构对账
```

管理面沿用平台 `R<T>` 响应与 springdoc 文档;数据面提供**静态** OpenAPI(描述查询语法契约,不做 per-project 动态反射)。项目 CORS 白名单(`allowed_origins`)通过 `PATCH /studio/projects/{ref}` 配置。

**表管理契约通则**:

- 操作 ID 位置:POST 建表 / PATCH 改表 / PUT ACL / **POST reconcile** 放请求 **body** 的 `operationId`;DELETE 删表放 **query 参数** `?operationId=`(DELETE 不带 body)。均为客户端生成的 UUID(定时对账由服务端生成,见 §9.4)
- 幂等语义:同 `(project, operationId)` 重复提交,**操作指纹**(HTTP 方法 + 服务内路径含表名 + 操作类型 + 规范化 body,见 §9.2)一致时返回 `result_snapshot` 原结果(含删表重放);指纹不一致 → 409。指纹必含路径与操作类型——DELETE 无 body,仅凭 body 摘要无法区分「同 ID 删不同表」
- 列定义对象(建表与加列/改列共用):`{columnName, dataType, length, scale, nullable, defaultValue, unique, indexed, comment}`
- **默认值类型化模型(不接受原始 SQL)**:`defaultValue` 为 JSON 标量,服务端按目标列类型严格解析后**重新渲染为规范字面量**——数值列解析为数值、boolean 解析为 TRUE/FALSE、date/datetime 校验格式、varchar 转义后单引号包裹;仅 datetime 列的值恰为保留字 `CURRENT_TIMESTAMP`(大小写不敏感)时渲染为函数;解析失败或任何其他表达式 → 400;`text` / `json` 列不支持默认值(MySQL 限制,400)。客户端字符串(默认值、表/列注释)**一律不得拼接进 DDL 原文**,统一经类型化渲染器输出;注释限长(列 ≤ 1024、表 ≤ 2048 字符,对齐 MySQL 上限)并转义
- 索引命名规范:单列唯一 `uk_{columnName}`、单列索引 `idx_{columnName}`;生成名超过 MySQL 索引名 64 字符上限时(列名最长 64,加前缀可达 67/68),**截断列名并追加 8 位稳定哈希后缀**(如 `idx_{col 前 51 字符}_{SHA-256 前 8 位 hex}`,总长 ≤ 64,同列名恒定),不降低列名上限。**统一索引名分配器**(ADD、唯一/普通索引替换、owner 自动补索引、`RENAME INDEX` 共用):锁内读取全表现有索引名后分配——规范名未占用 → 直接使用;已由**目标索引自身**占用 → 按探测结果视为幂等(无需 DDL);被**其他索引**占用(对账导入的外部表可能在别的列上有恰好叫 `idx_email` 的合法索引)→ 生成稳定哈希后缀备用名并对备用名再次检测,哈希碰撞时按确定性序号继续探测,保证不与现存任何索引重名。**`unique=true` 天然含索引**,此时 `indexed` 忽略(不额外建普通索引),owner 自动补索引(§8.3)遇已 unique 列同样跳过

**建表请求契约(POST tables)**:body 为 `{operationId, tableName, comment, columns: [列定义…]}`;主键 `id bigint` 自增由服务端自动生成,columns 中出现名为 `id` 的列 → 400;响应返回表详情快照(结构同 GET 单表详情),并作为幂等重放的 `result_snapshot`

**改表请求契约(PATCH tables/{table},操作列表式)**:不采用声明式全量 diff(列缺失即隐式删列,危险),改表意图必须显式列出:

```json
{
  "operationId": "uuid",
  "allowLossy": false,
  "newTableName": null,
  "comment": null,
  "addColumns":    [{ 列定义 }],
  "dropColumns":   ["col_a"],
  "modifyColumns": [{ 列定义 }],
  "renameColumns": [{ "from": "old_name", "to": "new_name" }]
}
```

- 各操作字段均可选,但至少包含一项操作;同一列在同一请求中只能出现于一种操作(如同列既 drop 又 rename → 400)
- **`dropColumns` 与有损 `modifyColumns`(见 §13 类型兼容矩阵)要求顶层 `allowLossy=true`**,缺失则 400 并在错误信息中说明风险;Studio 前端据此做二次确认交互
- **主键列 `id` 不可删除/修改/重命名** → 400
- **owner_column 联动**:rename 涉及 owner 列时在同一平台库事务内更新 `baas_column` 与 `baas_table.owner_column`;modify 将 owner 列改出 bigint → 400;**drop 包含 owner 列为单一 fail-closed 操作**——同一 DDL 锁内先关闭该表全部 anon/authenticated ACL 开关并清空 `owner_column`(平台库同事务),再执行 DROP COLUMN,不存在「owner 已取消、ACL 仍开」的越权窗口(需 `allowLossy=true`,响应中明确告知 ACL 已被关闭)
- **表重命名**(`newTableName`):校验合法标识符(§12.2)、非 `_` 前缀、不与现有表或 tombstone 表同名;**`RENAME TO` 作为子句并入同一条 ALTER 语句**(MySQL 将 RENAME 定义为 ALTER option),与其他操作混用不产生部分成功;数据面 URL 随表名变化由调用方自行适配
- `unique` / `indexed` 开关与现状的差异生成 `ADD/DROP INDEX` 子句,并入同一条 ALTER 语句(§9.2);重命名列时同步 `RENAME INDEX` 保持索引命名规范
- **owner 列索引不变量(锁内按修改后的最终结构校验)**:当前 owner 列在改表后必须仍保留单列普通索引或单列唯一索引——`modifyColumns` 将 owner 列改为 `unique=false, indexed=false` → 400(否则 DDL 引擎会合法生成 DROP INDEX,产出 ACTIVE 但违反 owner 不变量的表,§9.4 只能事后判 CONFLICT);`unique=true → unique=false, indexed=true` 在**同一条 ALTER** 中以普通索引替换唯一索引;确需删除 owner 索引,必须先经 ACL PUT 取消 owner(触发 §8.3 fail-closed 关闭 ACL),再以独立 ALTER 删除
- **索引操作按实际名定位**:新建索引使用规范名;**删除、替换、重命名已有索引前,必须在锁内查询项目库 `information_schema.statistics`,按该列实际存在的单列索引名生成 DDL**——对账导入的表可能带非规范索引名(如 `foo_email`),按规范名 `idx_email` 生成 DROP/RENAME 会失败

**项目级对象授权(防 IDOR)**:upms 菜单/API 权限只解决「能否进入 Studio 功能」,不能替代项目归属检查——

- 项目列表仅返回 `owner_user_id = 当前平台用户` 的项目
- 所有 `/studio/projects/{ref}/**` 操作(详情、表、Key、用户、删除、对账)一律校验项目归属,不匹配返回 404(不泄露存在性)
- 仅持有专门 upms 权限码 `baas_admin` 的超级管理员可跨项目操作(以 authority 字符串直查实现;平台角色 authority 形如 `ROLE_<数字id>`,不存在字符串角色,见 §16.2 工程事实)
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
| `anon` | 自动追加 `AND owner IS NULL`(只见无主记录) | **请求体包含 owner 列即 400**,owner 落 NULL | 过滤条件自动追加 `AND owner IS NULL` |
| `service_role` | 全量 | 唯一允许显式指定 owner 的角色 | 唯一允许修改 owner 的角色 |

配置约束:开启 owner 策略的表若允许 `anon.insert`,**owner 列必须可空**(配置时校验,否则拒绝保存 ACL)。未配置 owner_column 的表 = authenticated/anon 可访问全表(受 ACL 开关约束),Studio 界面须明确提示这一语义。完整规则表达式引擎(对标 RLS policy)放二期。

配置面细则(`PUT .../tables/{table}/acl`,body 为 `{operationId, acl: {anon: {select,insert,update,delete}, authenticated: {…}}, ownerColumn}`):

- **所有 ACL PUT 的执行一律进入项目级双层 DDL 锁(§9.2),与改表、删表、对账串行化**(已 SUCCESS 的幂等重放例外:按 §9.2 统一入口顺序在取锁前直接返回原快照,不受他人持锁影响)——ACL/owner 配置依赖列类型、可空性、索引与表状态,这些正被并发 DDL 修改时,按旧结构做的校验会产出损坏的 owner 约束(如把正在被删除或改型的列设为 owner),或在「drop owner 列已关 ACL、DROP COLUMN 尚未执行」的间隙重新开启 ACL 打破 fail-closed 保证。纯开关变更也不例外
- **每次 ACL PUT 都以操作类型 `acl-config` 记入 `baas_ddl_log`**,幂等键即客户端 `operationId`,遵守 §7.3 统一幂等契约(指纹比对、`result_snapshot` 重放——重放只返回原快照,不覆盖其间发生的其他 ACL 更新)。无需 DDL 的分支走 PREPARED → METADATA_APPLIED(即 SUCCESS);需补索引时同一操作经 PREPARED → DDL_APPLIED → METADATA_APPLIED
- 设置 `ownerColumn` 时**在锁内重新校验**:表状态为 ACTIVE、列存在、类型 bigint、`anon.insert=true` 时列必须可空;**`ownerColumn` 不得是 `id`、主键列或自增列**(按实际结构校验,MVP 即 `ownerColumn != "id"`;违反返回 400,且不修改 ACL 或索引——归属标识与记录主键合并后,authenticated 插入会把主键强制写成 `jwt.sub` 导致主键冲突,anon 插入的自增主键必然非 NULL、`owner IS NULL` 再也查不到自己刚插入的行);**探测项目库索引现状**,该列尚无索引(含唯一索引)则自动补建——是否补索引由现状决定,不以历史 SUCCESS 记录判断(否则「owner 取消 → 索引被删 → 再次设置 owner」场景无法重建索引)
- **取消 owner 配置(ownerColumn 置 null)为 fail-closed 操作**:服务端在同一平台库事务内强制将该表全部 anon/authenticated ACL 开关一并关闭(owner 策略失效后原 ACL 语义变为全表访问,不允许静默保留),响应中明确告知;调用方如需继续开放访问须再次显式 PUT
- 取消 owner 配置不删除已建索引(无损保留)

## 9. 项目与 Schema 生命周期

### 9.1 项目状态机

```
PROVISIONING → ACTIVE → DELETING → DELETED
      ↓
    FAILED(可重试 provisioning 或清理)
```

创建流程:**先在平台库插入 PROVISIONING 状态的项目记录**(先有状态载体,再产生外部副作用),随后由 Provisioner 账号执行:建 database → 建 runtime 账号并仅授权本库 DML → 初始化系统表 → 补全元数据 → 置 ACTIVE。任一步失败置 FAILED 并记录步骤,支持幂等重试;非 ACTIVE 项目数据面一律 403。

### 9.2 DDL 操作

**跨库一致性事实(设计前提)**:项目库 DDL 与平台库元数据分属不同数据源,**不可能同事务**;MySQL 原子 DDL 仅保证单条 DDL 语句自身崩溃一致,DDL 隐式提交、不能与其他语句组成事务。因此一致性靠「单条 ALTER + 检查点 + 探测式重试 + 对账」保障,不声称事务性。

- **串行锁(双层)**:第一层 Redis 分布式锁,锁 key `baas:ddl:{projectId}`,**value = `owner_token`(本次执行唯一:实例标识 + 随机值;不能用 operationId——接管会复用同一 operationId,旧执行者恢复后将无法与新执行者区分)**;`operationId` 只作为业务幂等键。TTL 60 秒 + watchdog 自动续租(每 20 秒),**续租、检查点校验、释放全部为原子 compare 且只比较 `owner_token`**(Lua:value 匹配才延期/删除,禁止 GET+EXPIRE 两步);获取失败返回 409「该项目有 DDL 操作进行中」。**每个检查点推进前校验锁仍为本 `owner_token` 持有**,续租失败或校验不通过立即中止后续步骤。但 Redis 层无法中止**正在执行中**的 DDL(JDBC 阻塞在语句上,取消操作亦不可靠),因此第二层在**执行 DDL 的同一项目库连接**上取 `GET_LOCK('baas_ddl_{projectId}', 0)`,获取失败立即中止——即使 Redis 租约过期、后继操作抢到 Redis 锁,它在数据库边界拿不到 advisory lock,**不会与旧 DDL 并发**。**加锁与所有权顺序固定、DB 锁覆盖全操作**:Redis 锁(新 `owner_token`)→ `GET_LOCK` → 再验 Redis token 仍持有 → **原子取得日志所有权(按「日志所有权取得」条的四分支执行)** → 再验日志 `owner_token`/`status` 归属本执行者 → 才允许产生任何副作用(封死「校验后、取 DB 锁前停顿,他人接管完成,本方恢复拿到空闲 DB 锁执行陈旧 DDL」的窗口;所有权在锁内取得,而非先校验后取得);`GET_LOCK` 持有到**平台元数据与日志终态在平台库事务提交之后**才 `RELEASE_LOCK`(MySQL named lock 保持到显式释放或 session 终止,后者为崩溃兜底),不在 DDL 语句结束时提前释放
- **迟到写入隔离(fencing)**:RUNNING 期间 `baas_ddl_log` 的一切状态推进——检查点、SUCCESS/FAILED——均为条件更新 `WHERE owner_token = ? AND status = 'RUNNING'`;丢锁旧执行者恢复后,其续租(token 不匹配)、释放(token 不匹配)与日志写入(条件更新 0 行)全部落空。**平台元数据变更与对应日志检查点必须在同一平台库事务内提交,并以该 owner_token 条件更新作为事务守卫——条件更新影响 0 行则整笔回滚**,封死「他人已接管完成、本方恢复后仍覆盖平台元数据」的窗口(仅日志 CAS 失败、元数据却已写入的结果不允许存在)。**owner_token 只隔离同一条日志行;不同 operationId 之间的迟到覆盖由下一条项目级 epoch 拦截**
- **项目级单调 fencing(`ddl_fence_epoch`)**:owner_token 守卫对「A 丢失双锁 → B 以**不同 operationId** 完成操作 → A 恢复后写自己的日志行(token 仍匹配,守卫恒过)」无效,A 仍可能用陈旧快照覆盖 B 的新元数据(若 A/B 是反向 ACL 更新甚至会重新开放匿名权限)。因此:每次真正进入执行路径时,在所有权短事务中**原子递增 `baas_project.ddl_fence_epoch`** 并把新值写入本操作日志行(`fence_epoch`);**其后一切平台元数据、检查点与终态事务必须 `SELECT ... FOR UPDATE` 锁定项目行并校验「当前项目 epoch = 执行者 epoch」,不匹配整笔回滚**;项目删除、项目物理清理等无普通操作日志的参与者同样在其事务中递增并校验同一 epoch。Redis 锁(互斥)、GET_LOCK(DB 边界防并发 DDL)、owner_token(单日志隔离)、epoch(项目级顺序隔离)四层并存,各解决一个层面
- **日志所有权取得(四分支,均在双层锁内原子执行)**。**事务提交边界**:所有权变更(INSERT/CAS)与对应的表状态置位(CREATING/ALTERING)、`step = 'PREPARED'` 写入、**项目 `ddl_fence_epoch` 的原子递增与落表(写入本操作日志行)**构成**一个短平台库事务,必须在任何项目库副作用之前提交**——禁止把所有权与后续元数据更新放进同一个长事务(否则「DDL 已生效、平台事务回滚」会留下无日志或旧所有权的真实 DDL);**事务内统一顺序**:① `SELECT baas_project ... FOR UPDATE`;② 计算并更新 `ddl_fence_epoch = 旧值 + 1`;③ 执行对应分支的 INSERT/CAS,**四分支一律写入 `fence_epoch = :newEpoch`**;④ 分支 CAS/INSERT 失败(0 行/唯一键冲突需改走他支)则**整笔回滚,项目 epoch 增量一并撤销**。该事务**提交成功后,锁内重读确认 `owner_token = :newToken AND status = 'RUNNING' AND fence_epoch = :newEpoch`,才可执行项目库 DDL**;提交失败或结果不确定时不得产生任何项目库副作用,只能锁内重读判定。所有权确立后才进入 RUNNING fencing。**step 语义(只前进不回退)**:仅新操作在所有权事务中初始化 `step = 'PREPARED'`;PENDING cleanup 预建时已是 PREPARED,认领保持不变;**FAILED 重试与陈旧 RUNNING 接管保留原 step**(如已达 DDL_APPLIED 则据此探测续跑,重置会破坏检查点与审计语义)。四分支:
  - **新操作**:`INSERT` 日志行(`status = 'RUNNING'`、`owner_token = :newToken`、`step = 'PREPARED'`、`fence_epoch = :newEpoch`);`(project_id, operation_id)` 唯一键冲突说明记录已存在 → 锁内重读,按其余分支处理
  - **FAILED 重试**:`UPDATE baas_ddl_log SET owner_token = :newToken, status = 'RUNNING', retry_count = retry_count + 1, fence_epoch = :newEpoch WHERE id = :id AND owner_token = :observedToken AND status = 'FAILED'`;0 行(被并发重试者抢先)→ 409。并发 FAILED 重试有且仅有一个执行者成功
  - **陈旧 RUNNING 接管**:见「陈旧 RUNNING 接管」条的 CAS(同样 `SET fence_epoch = :newEpoch`)
  - **待执行 cleanup(PENDING)**:`UPDATE baas_ddl_log SET owner_token = :newToken, status = 'RUNNING', fence_epoch = :newEpoch WHERE id = :id AND status = 'PENDING' AND owner_token IS NULL`;CAS 成功才执行锁内复核(按表 ID 重读校验);**未到期(`deleteAfter` 未至)不认领、保持 PENDING**,不得置 SUCCESS;多实例并发认领仅一个成功
- **超时分离**:DDL 专用执行超时(默认 5 分钟,可配,§13),与数据面 5 秒查询超时无关;超时后结果不确定,不盲判失败,由重试探测确认
- **检查点**(`baas_ddl_log.step`):`PREPARED`(校验通过、日志已落)→ `DDL_APPLIED`(项目库 DDL 已确认生效)→ `METADATA_APPLIED`(平台元数据已更新,即 SUCCESS)。**删表操作不含项目库 DDL**:PREPARED → METADATA_APPLIED(写 tombstone + `deleteAfter` 即 SUCCESS,快照可重放);到期物理 DROP 是**独立的内部操作**(操作类型 cleanup-drop),其 **operation_id(服务端 UUID)在 API 删除写 tombstone 的同一平台库事务中预生成并落 `baas_ddl_log`(`step = 'PREPARED'`、`status = 'PENDING'`、`owner_token = NULL`,记录不可变表 ID 与内部指纹)**,清理任务只通过 PENDING 认领 CAS(见「日志所有权取得」条)认领这条预建记录、不自行生成。**cleanup-drop 无客户端重试者,滞留状态由清理调度器周期扫描发现并处置**:到期 PENDING → 认领 CAS;FAILED → FAILED 重试 CAS(调度器充当重试者);RUNNING 且 Redis 锁已不属于其 `owner_token`(认领后崩溃)→ 陈旧 RUNNING 接管;SUCCESS → 跳过。**清理任务取得双层锁后必须按表 ID 重读元数据行,确认该行仍存在、状态为 DELETED 且 `deleteAfter` 已到期,方可执行 DROP**;行不存在(已被其他实例清理)、ID 不符或状态非 DELETED(同名重建的是新表 ID,或已被恢复)一律**不得 DROP**,该 cleanup 记录置 SUCCESS 且 `result_snapshot` 标记 no-op(校验不通过即目标已不存在或不再归本操作管,任务目的已消失,不算失败)——双层锁只能阻止并发,识别不了「A 清理完、用户重建同名表、B 的陈旧任务才拿到锁」的顺序竞态,防误删只能靠表 ID + 锁内重读。失败独立重试,不影响 API 删除操作的已完成状态
- **探测式重试**:FAILED/中断后同操作 ID 重试,先查项目库 `information_schema` 判定 DDL 是否已生效——已生效则跳过 DDL 直接续跑元数据步骤;未生效则重新执行整条 DDL;**不盲目重放**
- **操作指纹**:HTTP 操作的 `request_hash` = SHA-256(HTTP 方法 + 服务内路径(含 projectRef 与表名)+ 操作类型 + 规范化 body,DELETE 的 body 为空串);**内部操作单独定义规范载荷**——采用带版本的行式编码(UTF-8,字段顺序固定,`\n` 分隔,`deleteAfter` 为 ISO-8601 秒级),cleanup-drop 为 SHA-256 of `"v1\nkind=cleanup-drop\nprojectId={id}\ntableId={id}\ndeleteAfter=2026-07-25T12:34:56\n"`;定时对账为 SHA-256 of `"v1\nkind=reconcile\nprojectId={id}\noperationId={uuid}\ntrigger=scheduled\n"`。内部指纹在创建日志记录时一次性生成,重试/接管只按持久化的不可变字段复核、不重新计算。`(project_id, operation_id)` 联合唯一,同时存操作类型、目标表名、指纹与 `result_snapshot`
- **统一入口顺序**(所有表管理、ACL 与对账操作,消除「已成功的重放被他人持锁挡成 409」):鉴权与指纹计算 → 查询日志 → 指纹不一致 → 409;**SUCCESS → 直接返回 `result_snapshot`,不取任何锁**;仅**新操作、FAILED 重试、陈旧 RUNNING 接管**三类进入双层锁,并在锁内按「日志所有权取得」条重新查询/执行对应分支(锁外的初查只用于快速路径,不作为执行依据;内部 cleanup 的 PENDING 认领同样在双层锁内进行)
- **陈旧 RUNNING 接管**:进程崩溃会留下无人推进的 RUNNING 记录(锁过期后表将永久停在 CREATING/ALTERING)。同操作 ID + 同指纹的重试请求(内部 cleanup-drop 由清理调度器充当重试者)遇到 RUNNING 时,若确认 Redis 锁已不被该记录的 `owner_token` 持有(key 不存在或 value 为其他 token)且自己能以**新生成的 `owner_token`** 取得双层锁,则通过**条件 UPDATE(CAS:`SET owner_token = :newToken, fence_epoch = :newEpoch WHERE owner_token = :旧值 AND status = 'RUNNING'`)**接管该记录并按检查点探测续跑;接管失败或锁仍被持有 → 409。接管后旧执行者的一切迟到操作被上一条 fencing 规则拒绝
- 改表的全部变更(含 `RENAME TO`、`ADD/DROP/RENAME INDEX`)**合并为一条 `ALTER TABLE` 语句**执行,不存在多语句间的部分成功
- 失败置 FAILED 并记录脱敏错误信息;`ddl_text` 中默认值字面量以占位符脱敏记录(§6.1)

### 9.3 删除流程

删项目:置 DELETING(数据面即刻阻断)→ 吊销全部 API Key → 关闭连接池 → 软删元数据 → 延迟 N 天(默认 7)物理 DROP DATABASE 与账号。删表同理(阻断 → 软删 → 延迟 DROP)。**延迟 DROP 期间同名对象为 tombstone 状态,禁止重建同名项目 ref / 同名表**,物理清理完成后方可复用名称。

**项目生命周期与 Plan B DDL 的串行化(Plan A 现有实现未取锁,Plan B 须改造)**:

- 项目删除在状态 CAS 为 DELETING **之前**取得同一项目双层 DDL 锁(§9.2),锁内重查项目状态后再转移,并在状态转移事务中**递增并校验项目 `ddl_fence_epoch`**(§9.2,项目删除无普通操作日志,也必须参与项目级 fencing)——否则已通过 ACTIVE 校验的长 ALTER 会与项目删除交叉
- 所有 Plan B 操作(建/改/删表、ACL、对账、cleanup)在**取得锁后、写日志所有权前**再次确认项目为 ACTIVE(项目级 cleanup 除外,其复核 DELETING)
- DELETING 提交后阻止项目连接池新借用并完成 drain;**named lock 使用不计入项目池的专用管理连接**(如 Provisioner 数据源,GET_LOCK 为 MySQL 实例级命名锁,不要求连接到项目库),避免锁连接被 drain 波及
- 项目物理清理(DROP DATABASE)同样取双层锁,锁内复核项目仍为 DELETING 且已到期,并递增校验同一 `ddl_fence_epoch`,方可执行
- 表级 cleanup 遇到非 ACTIVE 项目时不得执行,与项目清理靠同一把项目锁互斥,不会并发操作项目库

### 9.4 对账

手动触发(`/reconcile`,body 携带客户端 `operationId`)+ 可选定时(`@Scheduled` + 配置开关,**默认关闭**,MVP 以手动为主)。对账日志记录**触发来源(MANUAL/SCHEDULED)**。**定时对账的陈旧任务发现(与 cleanup 调度器同型,扫描与决策必须在锁内重做)**:锁外扫描只能作为快速路径(决定是否值得取锁),不得作为执行依据;**取得双层锁后必须重新扫描本项目的 SCHEDULED reconcile 记录**——FAILED → 对原记录执行 FAILED 重试 CAS;RUNNING 且 Redis 锁已不属其 owner_token → 对原记录陈旧接管;RUNNING 且锁仍被持有 → 本轮直接跳过;**锁内确认不存在 RUNNING/FAILED 后才允许以服务端生成的新 operationId 创建新操作**(内部指纹见 §9.2)。否则多实例下「双方锁外读空 → A 插入 RUNNING 后崩溃 → B 沿用锁外决策再插一条」会违反下述约束。**同一项目同一时刻最多存在一个未终结(RUNNING 或 FAILED)的 SCHEDULED reconcile**——若每轮都直接换新 ID,遗留的 FAILED/陈旧 RUNNING 将永远无人认领。**范围仅表结构**:以项目库 `information_schema` 为准修正 `baas_table`/`baas_column`;ACL、owner 配置、密钥属于操作意图,以平台库为唯一事实源,不参与对账。**对账跳过非 ACTIVE 项目及软删/tombstone 表——物理表仍存在不构成「复活」软删除对象的依据**。**对账全程纳入 §9.2 统一日志与所有权模型**:操作类型 `reconcile`(项目级,目标表名与表 ID 为 NULL),走统一入口顺序与双层锁,按四分支取得 RUNNING 所有权;**对账产生的全部平台元数据修正、报告快照(`result_snapshot`)与 METADATA_APPLIED/SUCCESS 在同一平台库事务中提交,采用双重守卫**:① `SELECT ... FOR UPDATE` 锁定 `baas_project` 行并校验 `ddl_fence_epoch = 执行者 fence_epoch`;② 日志更新同时校验 `owner_token`/`status`/`fence_epoch`;任一失败整笔回滚——读完 `information_schema` 后丢双锁、他人以**不同 operationId** 完成并发 ALTER 的场景下,陈旧对账因**项目 epoch 不匹配**整笔回滚(此场景旧日志行的 owner_token 仍匹配,单靠它拦不住,见 §9.2 项目级 fencing)。对账报告即 `result_snapshot`(修正/导入/冲突清单),幂等重放返回原报告。

比对范围覆盖 Plan B 管理的**全部结构要素**:列集合、类型/长度/精度、可空、默认值、注释、主键/自增、唯一索引与普通索引。

**ACTIVE 准入谓词**(导入、按库修正、CONFLICT 恢复 ACTIVE 三条路径前统一校验):`TABLE_TYPE = 'BASE TABLE'`(VIEW 不进 MVP,§15)、`ENGINE = 'InnoDB'`(MyISAM/MEMORY 等非事务引擎破坏 §7.1 批量插入单事务原子契约)、无表级触发器(产生元数据模型不可见的 DML 副作用)、表名与全部列名通过 §12.2 标识符正则与保留字检查(导入不得绕过管理 API 的标识符安全边界)、主键不变量(§13:唯一主键为 `id` bigint 自增)、无生成列/外键等不支持结构、类型与索引结构可映射(**类型比较基于 §13 逻辑—物理规范化后的逻辑模型**,如 tinyint(1) ↔ boolean)。**违反时的处置按元数据是否存在拆分**:已有元数据的表 → `baas_table.status = CONFLICT`(状态有载体);**无元数据的外部表 → 不创建元数据行,在对账报告中记为 `REJECTED_IMPORT` 及原因**(CONFLICT 是 `baas_table.status`,没有行就没有状态可置;数据面因无元数据继续 404),结构修复后下次对账按正常路径导入并置 ACTIVE。

逐表处理规则:

| 情形 | 处理 |
|---|---|
| 元数据有、项目库无该表 | 表置 CONFLICT |
| 项目库有、元数据无(非 `_` 前缀) | **先过 ACTIVE 准入谓词**:通过 → 导入元数据,状态 ACTIVE,**ACL 默认全关**(安全兜底);不过 → **REJECTED_IMPORT 报告留痕,不创建元数据行** |
| **主键不变量破坏**:缺少 `id` 列、`id` 非 bigint/非自增/非唯一主键、复合主键或存在额外主键(§13 固定主键约束) | 准入谓词失败:有元数据 → 表置 CONFLICT;无元数据 → REJECTED_IMPORT |
| **不支持结构**:生成列(`information_schema.columns` 的 EXTRA/GENERATION_EXPRESSION 探测)、外键(约束表探测)、VIEW、非 InnoDB 引擎、表级触发器、非法标识符 | 准入谓词失败:有元数据 → 表置 CONFLICT,不做普通修正(元数据模型无法表达,压缩会静默丢失结构信息);无元数据 → REJECTED_IMPORT |
| 结构差异且库侧可映射白名单(含默认值/注释/索引差异) | 以库为准修正 `baas_table`/`baas_column`(索引仅记录 unique/indexed 布尔位;后续对该索引的 DDL 按 §7.3「索引操作按实际名定位」规则查 `information_schema.statistics` 生成) |
| 列类型超出白名单(如 float) | 表置 CONFLICT |
| **索引结构无法映射为单列布尔模型**(同列重复单列索引、复合索引、复合唯一等) | **表置 CONFLICT,不得压缩成布尔字段后恢复 ACTIVE**(布尔位丢失结构信息,后续 DDL 无法正确定位) |
| **owner 安全约束破坏**:owner 列缺失、类型非 bigint、或索引丢失 | **表置 CONFLICT 并阻断数据面,不做普通修正**(owner 策略的正确性依赖这三项,静默修正会掩盖越权风险) |
| CONFLICT 表再次对账且结构已一致(含 owner 约束恢复) | 恢复 ACTIVE |
| 软删 tombstone 表 / 非 ACTIVE 项目 | 跳过 |

### 9.5 表状态机(`baas_table.status`)

```
CREATING → ACTIVE                          建表成功
CREATING → FAILED                          建表失败(同操作 ID 幂等重试)
ACTIVE   ⇄ ALTERING                        改表开始/成功返回(执行中,短暂)
ALTERING → CONFLICT                        改表中断或失败(保持阻断)
ACTIVE   → CONFLICT                        对账发现结构不一致
CONFLICT → ACTIVE                          探测式重试续跑成功,或对账确认结构一致
ACTIVE   → DELETED(tombstone,+N 天,默认 7) → 到期物理 DROP 并删除元数据行(§9.3)
```

- **建表**:锁内先插 CREATING 元数据行(先有状态载体,同 §9.1;`unique(project_id, table_name)` 同时拦截与现有表及 tombstone 行的同名冲突)→ 执行 `CREATE TABLE` → 写列元数据 → 置 ACTIVE。失败置 FAILED,同操作 ID 幂等重试
- **改表**:锁内置 ALTERING(数据面短暂阻断——DDL 已生效而元数据未更新的窗口内,按旧元数据构建的查询会撞上新结构)→ 按 §9.2 检查点执行 → 成功回 ACTIVE;中断/失败置 CONFLICT(保持阻断),同操作 ID 探测式重试续跑成功后回 ACTIVE,或由对账处置
- **删表**:置 DELETED + `deleteAfter`(数据面即刻阻断)→ 延迟清理任务到期物理 `DROP TABLE` 并**物理删除元数据行**——行删除后唯一键自然放开,名称即可复用(§9.3)
- **删列**:请求带 `allowLossy=true` 后**立即物理 `DROP COLUMN`**,数据不可恢复,记审计日志;列不做延迟清理,列名可立即复用
- 非 ACTIVE 状态(CREATING/ALTERING/FAILED/CONFLICT/DELETED)的表数据面一律阻断(执行面为 Plan C)

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

- BaaS 的 JWT secret、数据库凭据、refresh 重放密文等一律使用 **BaaS 专用加密器**,算法 AES-256-GCM(随机 IV),AAD 绑定 `project_id + 字段类型 + 记录 ID`,防密文跨记录替换
- 主密钥只能来自**环境变量或部署 Secret**,不得入库、入 Nacos 配置中心或提交 Git;未配置主密钥时 BaaS 模块启动失败(fail-fast),不回退到默认 Jasypt
- 密文格式 `v1:{keyId}:{base64(iv|ciphertext|tag)}`,预留主密钥轮换(新密钥写入用新 keyId,读取按前缀路由)
- **API Key 不走 AES 加密**:opaque key 本身为高熵随机串(≥256 bit),存储固定用 **SHA-256** 摘要(高熵输入下无需加盐或 pepper,也就无额外密钥生命周期负担);比较采用常量时间算法

### 12.2 通用防线

- 注入防线:表名/列名对照元数据白名单 + 值全部 PreparedStatement 参数绑定;DDL 层标识符限定 `^[a-z][a-z0-9_]{0,63}$` 并过滤 MySQL 保留字
- `_` 前缀系统表对数据 API 完全不可见
- CORS:数据面支持 per-project 允许来源配置(`baas_project.allowed_origins`),MVP 默认 `*` 可收紧,**默认 `*` 时固定 `allowCredentials=false`**。OPTIONS 预检不携带 apikey,由 CORS Filter 在 ApiKeyAuthFilter **之前**处理:仅按 URL projectRef 查平台元数据,不创建任何项目库连接
- Auth 防暴力:基于 Redis 的登录/注册失败计数限速(每邮箱 + 每 IP)
- 日志脱敏:password、key、JWT 不落日志;DDL 与密钥操作入 `baas_audit_log`
- 限流:gateway/sentinel 基础 QPS 阈值 + 服务内资源限制(第 13 节)兜底慢查询

## 13. 资源限制(MVP 默认值,可配)

| 项 | 限制 |
|---|---|
| 请求体大小 | 1 MB |
| 批量插入行数 | 1000 行 |
| 过滤条件数量 | 每请求 20 个 |
| SQL 执行超时 | 5 秒(JDBC queryTimeout,仅数据面) |
| DDL 执行超时 | 5 分钟(独立于数据面超时,超时后按 §9.2 探测式重试确认结果) |
| 单项目连接池 | max 10;全服务总连接预算另设上限 |

### 表编辑器能力边界

- 类型白名单:`bigint / int / decimal(p,s) / varchar(n≤4096) / text / boolean / date / datetime / json`
- **boolean 的逻辑—物理规范化层**(MySQL 将 BOOLEAN 作为 TINYINT(1) 同义词,`information_schema` 回读为 tinyint):① DDL 固定渲染 `boolean → TINYINT(1)`;② 对账/准入把 `DATA_TYPE = 'tinyint'` 且 `COLUMN_TYPE = 'tinyint(1)'` 映射回逻辑 boolean,**其他 tinyint 变体(如 tinyint(4)、unsigned/zerofill)拒绝映射**(视同白名单外);③ `COLUMN_DEFAULT` 的 0/1 分别规范化为 JSON false/true;④ 类型兼容判定、导入、按库修正与探测式续跑**全部基于规范化后的逻辑模型比较**,避免平台自建 boolean 列被误判出白名单或每轮对账产生默认值漂移
- 默认值:类型化常量或 `CURRENT_TIMESTAMP`(仅 datetime 列),按 §7.3 类型化模型解析渲染;`text`/`json` 列不支持默认值
- 约束:主键固定为自增 `id bigint`(建表自动生成);单列唯一、单列索引;建表语句**显式 `ENGINE=InnoDB`**(数据面事务契约依赖 InnoDB,§9.4 ACTIVE 准入谓词同此要求)
- 改表能力(全能档):加列、删列、改类型/长度、改可空/默认值/注释、单列唯一/索引增删、重命名列/表;契约见 §7.3,破坏性操作需 `allowLossy` 确认
- **不支持**:复合主键/复合索引/复合唯一、外键、生成列(`baas_column` 模型即按此约束设计)

### 类型兼容矩阵(MODIFY 判定)

只维护**无损集合**,集合外的转换一律要求 `allowLossy=true`(不维护禁止矩阵):

| 无损转换 | 说明 |
|---|---|
| `int → bigint` | 扩位 |
| `varchar(n) → varchar(m)`,m > n | 扩长 |
| `varchar → text` | 扩容 |
| `decimal(p,s) → decimal(p',s')`,s' ≥ s 且 p'−s' ≥ p−s | 整数位与小数位均不缩 |
| `date → datetime` | 补时间位 |
| 同类型仅改注释/默认值/可空改为更宽 | 属性变更 |

集合外转换(缩长度、`text→varchar`、跨类别如 `varchar→int` 等):不带 `allowLossy` → 400,错误体 `hint` 说明风险;带 `allowLossy` → 下发 DDL,**保持 MySQL 严格模式**,数据不兼容时语句失败返回 409,不静默截断。可空→非空在表内存在 NULL 行时同样由严格模式拦截返回 409。

## 14. 测试策略

- **单元测试**(回归主防线):查询语法解析器、SQL 构建器、角色/ACL/owner 策略解析、JWT 与 key 校验
- **集成测试**:Testcontainers 真 MySQL,覆盖「建项目状态机 → 建表 → CRUD 各操作符与语义细则 → signup/login/refresh/logout → ACL 与 owner 策略拦截 → key 吊销生效」全链路
- **安全场景必测**:owner 伪造/转移(各角色 × INSERT/PATCH 携带 owner 列)、URL/apikey/JWT 三项目标识不一致、secret key 携带 JWT、Studio 跨项目越权(IDOR,替换 `{ref}`)、并发 refresh(grace 窗口内/外)、连续 JWT 轮换、**紧急轮换后原 current 与 previous 签发的 JWT 均立即 401 且 refresh token 仍可换新**、软删除后对账不复活、Cloud 与 Boot 两种形态的鉴权链各自生效
- **Plan B(表管理与 DDL)必测**:类型兼容矩阵判定单测(无损/有损分类全覆盖);有损转换不带 `allowLossy` → 400、带且数据不兼容 → 409 不截断、可空→非空含 NULL 行 → 409;rename owner 列联动更新 `owner_column`、`id` 列保护;**drop owner 列 fail-closed**(ACL 同步全关、无越权窗口)与**取消 owner 配置强制关 ACL**;tombstone 同名禁重建(建表与表重命名两条路径)、物理清理后名称可复用;对账各情形(§9.4)各一例,**含 owner 约束破坏(列缺失/非 bigint/索引丢失)置 CONFLICT 不修正**;DDL 锁互斥、锁续租(原子 CAS)与锁丢失中止、**watchdog 停顿超过 TTL 时 DB advisory lock 兜底**(第二操作拿到 Redis 锁但在 GET_LOCK 处被拒,不与旧 DDL 并发);幂等重放(指纹一致返回快照、**指纹不一致 409**,含 DELETE 同 ID 删不同表被指纹区分)、`DDL_APPLIED` 断点探测式续跑、**陈旧 RUNNING 接管**(模拟进程崩溃后 CAS 接管续跑)、**接管后旧执行者隔离**(A 超时、B 接管后:A 续租失败、无法释放 B 的锁、日志条件更新 0 行,不覆盖 B 的检查点;**含 A 在取 GET_LOCK 前停顿、A 在写平台元数据前停顿两个窗口**——恢复后均不产生陈旧 DDL 或元数据覆盖,元数据事务因守卫 0 行整笔回滚);**并发 FAILED 重试仅一个执行者 CAS 成功**、连续多次失败 retry_count 递增;**陈旧 cleanup 不误删同名新表**(旧表清理完成、同名重建后,预建 cleanup 记录按表 ID 校验不通过、不执行 DROP)、**未到期 PENDING 不被认领**(保持 PENDING、不置 SUCCESS)、**多实例并发认领同一 PENDING 仅一个 CAS 成功**、**PENDING→RUNNING 后立即崩溃可被调度器自动接管**、**DROP 已执行而终态提交前崩溃时探测式续跑**、**FAILED cleanup 由调度器自动重试**;**所有权事务提交失败不产生任何项目库副作用**、**所有权事务提交成功后崩溃可被接管续跑**、**DDL_APPLIED 状态下重试/接管不重置检查点**;**陈旧对账不覆盖新元数据**(对账读完结构后丢锁、并发 ALTER 完成,旧对账整笔回滚);**跨 operationId 的项目级 epoch fencing**(A 在最后一次锁校验后停顿并丢失双锁,B 以不同 operationId 完成操作,A 恢复后整笔事务因项目 epoch 不匹配回滚;至少覆盖 reconcile-vs-ALTER 与 ACL 关闭-vs-开启两组);**SCHEDULED reconcile 的 RUNNING 崩溃由下一调度周期接管**、**FAILED 自动重试**、**多实例调度不产生重复任务**(两个调度器锁外同时读空,第一个写入 RUNNING 后崩溃,第二个锁内重查后接管原记录、不创建新记录);**迁移后 Plan A 建项目路径不改造仍成功且 `ddl_fence_epoch` 初始为 0**;**四分支所有权取得后日志 fence_epoch 与项目 epoch 相等**(参数化覆盖新操作/FAILED 重试/RUNNING 接管/PENDING 认领);**管理 API 不得删除 owner 列最后一个索引**(modifyColumns unique=false,indexed=false → 400;unique→普通索引同一 ALTER 替换);**非规范索引名可被正确定位**(外部建 `foo_email` 索引 → reconcile 导入 → PATCH 关闭索引/重命名列,DDL 按 information_schema 实际名生成且执行成功);**ACL PUT ownerColumn=id → 400 且不改 ACL/索引**;**对账准入拦截**——已有元数据的表违反约束置 CONFLICT、无元数据的外部表拒绝导入且报告留痕 REJECTED_IMPORT(两类断言分开,不得同时断言「不导入」与 `status=CONFLICT`),用例覆盖:无 `id`、`id` 类型/自增/主键性质错误、复合主键、生成列、外键、**VIEW、MyISAM、表级触发器、非法表名/列名**;**建表语句显式 ENGINE=InnoDB**;**boolean 规范化闭环**(创建含 true/false 默认值的 boolean 列 → 查询 information_schema 回读 tinyint(1) 与 0/1 → 对账无差异不产生漂移;tinyint(4)/unsigned 变体拒绝映射);**索引名占用探测**(其他列的外部索引占用 `idx_email`/`uk_email` 后,email 列新增索引、唯一/普通替换及列重命名仍成功,分配器落到备用名);**长 ALTER 与项目删除互斥**、**表级 cleanup 与项目 DROP DATABASE 不并发**(同一项目锁);**SUCCESS 重放不取锁**(他人长时间持有 DDL 锁时,已成功操作的同 ID 重放仍立即返回原快照而非 409);**ACL PUT 与改表串行化**(并发 ALTER 删/改 owner 列时 ACL 配置不产出损坏的 owner 约束)、**ACL 幂等重放不覆盖新配置**(请求成功 → 另一请求修改 → 原 operationId 重放仅返回旧快照);**删表 tombstone 快照重放**与 cleanup-drop 独立取锁重试;**超过 255 字符的注释/默认值元数据双写成功**(验证迁移扩容);**ACL 索引被外部删除后再次设置 owner 可重建**;**64 字符列名生成合法索引名**(截断 + 哈希后缀 ≤ 64);**DDL 注入尝试**(默认值/注释携带引号、注释符、子查询等恶意载荷,断言渲染后 DDL 无原样拼接)(Testcontainers 真 MySQL)
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

## 16. 实施规划与会话交接

本节供后续会话(全新上下文)接手时定位进度与方法。

### 16.1 计划拆分

BaaS 核心 MVP 拆为 5 份实施计划,每份独立产出可运行、可测试的软件,按依赖顺序执行:

| 计划 | 范围 | 对应 spec 章节 | 依赖 | 状态(2026-07-18) |
|---|---|---|---|---|
| **A 项目底座与生命周期** | 模块骨架(`ai-work-baas`,端口 4010)、元数据库、AES-GCM 加密器、API Key 体系、项目状态机与延迟清理、项目连接池注册表、Studio 项目管理 API(含 IDOR 防护) | §4、§6、§8.1、§9.1/9.3、§10、§12.1 | — | **已实现**(PR #13 已合入 develop) |
| **B 表管理与 DDL** | 建/改/删表(元数据 + 真实 DDL,改表为全能档:加/删列、改类型/长度、重命名列/表,含 `allowLossy` 确认与类型兼容矩阵)、Redis 串行锁 + 操作 ID 幂等(`baas_ddl_log`)、表状态机(§9.5)、表级 ACL 与 owner 列配置(bigint 校验/自动建索引/anon.insert 要求可空)、软删 tombstone 与同名禁重建、`information_schema` 对账 | §7.3(表管理契约)、§8.2/8.3(配置面)、§9.2/9.4/9.5、§13(表编辑器边界与兼容矩阵) | A | 设计细化至 v21(v8 后经十三轮评审修订,v21 消除十三轮 2 P1;范围含项目删除/清理取锁改造、项目级 epoch fencing、ACTIVE 准入谓词、类型规范化层与索引名分配器),待复审;计划未写 |
| **C 数据面 REST** | PostgREST 风格解析器与 SQL 构建器、`/rest/v1/{table}` 动态 CRUD 语义细则、ApiKeyAuthFilter + URL/apikey/JWT 三方一致性、owner 行策略注入、CORS Filter(先于鉴权、仅查元数据)、错误体映射、资源限制、Cloud/Boot 双形态入口落地、数据面静态 OpenAPI | §5、§7.1/7.4、§8.2/8.3(执行面)、§11、§12.2、§13 | A、B | 未写 |
| **D 终端用户 Auth** | `/auth/v1/*`:signup/login、`_sessions`/`_refresh_tokens`、refresh 行锁事务 + 10 秒 grace 加密重放、logout/改密撤销会话、JWT 签发验签(kid 双版本)、常规/紧急轮换端点、防暴力限速 | §7.2、§6.1/6.2、§12.2 | A、C | 未写 |
| **E Studio 前端** | ai-work-ui 的 BaaS 控制台:项目列表/详情、可视化表编辑器、ACL 与 owner 配置、API Key 管理(明文仅显示一次),遵循 ai-work-ui/DESIGN.md | §7.3 的界面化 | A、B(可与 C/D 并行) | 未写 |

MVP 之后(各自另行「设计 → 计划」,不属于上述 5 份):插件市场 MVP → MCP 插件(以 §7.3 管理面 API 为契约,作为市场首个插件)→ Storage → Realtime → Functions(见 §2)。

### 16.2 接手方法

1. **计划文档位置**:`docs/superpowers/plans/`(该目录被 .gitignore 忽略,仅本机保留;Plan A 为 `2026-07-17-baas-plan-a-foundation.md`)。若文件缺失,以本 spec 为准用 superpowers:writing-plans 重写。
2. **计划编写标准**(Plan A 五轮评审形成,后续计划直接沿用):无占位符;逐任务给出完整可编译的实现与测试代码(含 package/import);每步带确切验证命令与预期输出;资源文件用确定命令生成;测试类 `*Test` 命名,定向运行加 `-Dsurefire.failIfNoSpecifiedTests=false`。**写 Plan B~E 之前必须先读已合入的实际代码,不得凭 spec 推测接口。**
3. **流程**:writing-plans 写计划 → 需求方评审(通常多轮,逐条核实后修订)→ 通过后以 subagent-driven-development(推荐)或 executing-plans 执行;实现代码从 develop 新开 feature 分支(如 `feat/baas-foundation`),spec 修订在 docs 分支(当前 `docs/baas-core-mvp-design`)。
4. **已确认的工程事实**(写计划时直接采用):
   - upms 占端口 4000,`ai-work-baas` 用 **4010**
   - Spring Boot 4.0.7 受管 **Testcontainers 2.x**:依赖 `testcontainers-mysql` / `testcontainers-junit-jupiter`,类为 `org.testcontainers.mysql.MySQLContainer` 且无泛型
   - `SecurityUtils.getRoleIds()` 把 `ROLE_` 后缀 `Long.parseLong`,字符串角色不可用;BaaS 管理员判定用 upms 权限码 `baas_admin` 的 authority 字符串直查
   - 仓库默认 Jasypt(PBEWithMD5AndDES + dev 根密码明文)不得用于 BaaS 密钥(§12.1);主密钥只经 `System.getenv`
   - 已确认 `R.ok()` / `R.ok(T)` / `R.failed(String)` 存在,`AiWorkUser.getId()` 返回 `Long`
   - dynamic-datasource 的 Header/Session 解析链不可用于数据面(§4);BaaS 用自研 ProjectDataSourceRegistry
   - gateway 全局过滤器剥掉外部路径第一段;boot 形态 context-path `/admin` 且无网关(§5)
   - MySQL named lock(`GET_LOCK`)为 session 级独占锁,须由同一 session 释放或随连接终止释放;Plan A 现有代码逐次 `JdbcTemplate.execute` 每次可能从池中取不同连接,**Plan B 实现 §9.2 第二层锁时必须显式借用同一个物理 Connection 完成 GET_LOCK → DDL → RELEASE_LOCK**(如 ConnectionCallback 或手动 getConnection 持有)
   - Plan A 的项目删除(`ProjectLifecycleService` 直接 CAS DELETING、关连接池)与项目物理清理(`ProjectCleanupJob` 到期直接 DROP DATABASE)**均未取任何 DDL 锁**;Plan B 须按 §9.3 改造为先取项目双层锁再转移状态/执行清理

## 17. 修订记录

- **v21(2026-07-18)**:按 Plan B 十三轮复审(2 P1)修订。① boolean 逻辑—物理规范化层(MySQL BOOLEAN = TINYINT(1) 同义词,information_schema 回读 tinyint(1)/0/1):DDL 固定渲染 boolean → TINYINT(1);对账/准入把 DATA_TYPE=tinyint 且 COLUMN_TYPE=tinyint(1) 映射回逻辑 boolean,其他 tinyint 变体(tinyint(4)、unsigned/zerofill)拒绝映射;COLUMN_DEFAULT 0/1 规范化为 false/true;类型兼容判定、导入、修正、探测式续跑全部基于规范化逻辑模型(§13,§9.4 准入谓词同步标注);② 统一索引名分配器(ADD/唯一普通替换/owner 补索引/RENAME INDEX 共用):锁内读全表索引名,规范名未占用直接用、被目标索引自身占用视为幂等、被其他索引占用(如外部表他列恰有 idx_email)生成稳定哈希备用名并再检测、碰撞按确定性序号继续(§7.3)。§14 增补:boolean 规范化闭环(建列→回读→对账无漂移、变体拒绝映射)、索引名占用探测(占用后新增/替换/列重命名仍成功)。
- **v20(2026-07-18)**:按 Plan B 十二轮复审(2 P1)修订。① 「置 CONFLICT、不导入」缺状态载体(CONFLICT 是 `baas_table.status`,不导入即无行可置):处置按元数据是否存在拆分——已有元数据违反约束 → status=CONFLICT;无元数据的外部表 → 不创建元数据行,对账报告记 **REJECTED_IMPORT** 及原因,数据面因无元数据继续 404,修复后下次对账正常导入;测试相应拆为两类断言(§9.4/§14);② 统一 **ACTIVE 准入谓词**(导入、按库修正、CONFLICT 恢复三路径前必过):TABLE_TYPE='BASE TABLE'(拦 VIEW)、ENGINE='InnoDB'(拦 MyISAM/MEMORY,否则破坏 §7.1 批量插入单事务原子)、无表级触发器、表名与全部列名过 §12.2 正则与保留字、主键不变量、无生成列/外键、结构可映射;管理面建表语句显式 `ENGINE=InnoDB`(§9.4/§13)。§14 增补:准入拦截用例扩至 VIEW/MyISAM/触发器/非法标识符,显式 InnoDB 建表断言。
- **v19(2026-07-18)**:按 Plan B 十一轮复审(2 P1)修订。① ownerColumn 禁用主键:`ownerColumn` 不得是 `id`、主键列或自增列(MVP 即 `ownerColumn != "id"`,锁内按实际结构校验,违反 400 且不修改 ACL/索引)——归属标识与主键合并后 authenticated 插入强制写 `jwt.sub` 必产主键冲突、anon 插入的自增主键非 NULL 导致 `owner IS NULL` 查不到自己刚插入的行(§8.3);② 对账矩阵补主键不变量与不支持结构拦截:缺 `id`/`id` 非 bigint 非自增非唯一主键/复合或额外主键 → CONFLICT(导入与修正两路径均适用);生成列(EXTRA/GENERATION_EXPRESSION 探测)、外键(约束表探测)等 §13 不支持结构 → CONFLICT 不导入不修正;导入行明确先过这两类检查(§9.4)。§14 增补:ACL PUT ownerColumn=id → 400、对账拦截五例(无 id、错误 id、复合主键、生成列、外键)。
- **v18(2026-07-18)**:按 Plan B 十轮复审(3 P1)修订。① 四分支 SQL 补 `fence_epoch`(照 v17 SQL 实现会保留旧 epoch,终态守卫必然失败):所有权事务定为统一顺序「项目行 FOR UPDATE → epoch = 旧值 + 1 → 分支 INSERT/CAS 一律写入 fence_epoch=:newEpoch → 失败整笔回滚含 epoch 增量」,提交后重读同时验证 owner_token/status/fence_epoch;新操作 INSERT、FAILED 重试、RUNNING 接管、PENDING 认领四处 SQL 全部补齐(§9.2);② owner 列索引不变量:锁内按修改后最终结构校验,modifyColumns 将 owner 列改为 unique=false,indexed=false → 400,unique→普通索引在同一 ALTER 中替换,确需删索引须先 ACL PUT 取消 owner(fail-closed)再独立 ALTER(§7.3);③ 索引操作按实际名定位:新建用规范名,删除/替换/重命名前锁内查 `information_schema.statistics` 按实际单列索引名生成 DDL(对账导入的非规范名如 foo_email 按规范名操作会失败);对账遇同列重复单列索引/复合索引等无法映射结构时置 CONFLICT,不得压缩成布尔位恢复 ACTIVE(§7.3/§9.4)。§14 增补:四分支所有权后日志 epoch 与项目 epoch 相等、管理 API 不得删 owner 最后一个索引、非规范索引名定位。
- **v17(2026-07-18)**:按 Plan B 九轮复审(2 P1/1 P2)修订。P1——① §9.4 守卫表述与 §9.2 项目级 fencing 同步:对账终态事务改为**双重守卫**(`SELECT ... FOR UPDATE` 锁项目行校验 epoch + 日志更新校验 owner_token/status/fence_epoch,任一失败整笔回滚),失效原因更正为「项目 epoch 不匹配」——跨 operationId 场景下旧日志行 owner_token 仍匹配,单靠它拦不住;② SCHEDULED reconcile 的单任务约束补锁内重查:锁外扫描仅快速路径,取双层锁后重新扫描,FAILED 重试原记录/陈旧 RUNNING 接管/仍持锁则本轮跳过,锁内确认无 RUNNING/FAILED 才建新 operationId(§9.4)。P2——③ 迁移细节定稿:`baas_project.ddl_fence_epoch BIGINT NOT NULL DEFAULT 0` 并回填存量(Plan A `createProject()` 不设置该列,靠默认值免改造);`baas_ddl_log.fence_epoch BIGINT NULL`(历史与 PENDING 为 NULL,转 RUNNING 必须赋值);触发来源落列名 `trigger_source VARCHAR(16) NULL`(非 reconcile 为 NULL)(§6.1)。§14 增补:多实例调度不产生重复任务、迁移后 Plan A 建项目路径不改造仍成功且 epoch 初始为 0。
- **v16(2026-07-18)**:按 Plan B 八轮复审(1 P0/1 P1/1 P2)修订。P0——项目级 fencing 缺失,不同 operationId 可迟到覆盖(owner_token 只守卫自己的日志行:A 丢双锁 → B 以不同 ID 完成 → A 恢复写自己那行守卫恒过,可用陈旧快照覆盖 B 的新元数据,反向 ACL 更新甚至重新开放匿名权限):新增 `baas_project.ddl_fence_epoch`(BIGINT NOT NULL,迁移新增)与 `baas_ddl_log.fence_epoch`;所有权短事务中原子递增项目 epoch 并落日志行,其后一切平台元数据/检查点/终态事务 `SELECT ... FOR UPDATE` 锁项目行并校验「当前 epoch = 执行者 epoch」,不匹配整笔回滚;项目删除与物理清理等无普通日志的参与者同样递增并校验;Redis/GET_LOCK/owner_token/epoch 四层各司其职(§6.1/§9.2/§9.3)。P1——定时对账无陈旧任务发现与内部指纹:对账日志记录触发来源 MANUAL/SCHEDULED;定时器每轮先接管本项目遗留的 SCHEDULED FAILED(重试 CAS)/陈旧 RUNNING(接管),两者皆无才建新操作;同一项目最多一个未终结 SCHEDULED reconcile;内部指纹补 reconcile 载荷(v1 行式,kind/projectId/operationId/trigger)(§9.2/§9.4)。P2——§7.3 契约通则补 POST /reconcile 的 operationId 位置(body)。§14 增补:跨 operationId 项目级 epoch fencing(reconcile-vs-ALTER、ACL 关闭-vs-开启)、SCHEDULED reconcile 崩溃接管与 FAILED 自动重试。
- **v15(2026-07-18)**:按 Plan B 七轮复审(2 P1/1 P2)修订。P1——① 对账纳入统一日志与所有权模型:`operation_type` 增加 **reconcile**(项目级,目标表名/表 ID 为 NULL),`POST /reconcile` body 携带 operationId(定时触发服务端生成),走统一入口与四分支所有权;对账的全部平台元数据修正、报告快照与 METADATA_APPLIED/SUCCESS 同一平台库事务、owner_token 条件更新守卫,丢锁后的陈旧对账整笔回滚(§6.1/§9.4);② 项目删除生命周期与 Plan B DDL 串行化(实测 Plan A 删除与清理均未取锁):项目删除在 CAS DELETING 前取项目双层锁并锁内重查;所有 Plan B 操作取锁后写所有权前复核项目 ACTIVE;DELETING 后阻止新借用并 drain,named lock 用不计入项目池的专用管理连接;项目物理清理取锁并复核 DELETING 与到期;表级 cleanup 与项目清理靠同一把锁互斥(§9.3,§16.2 记录 Plan A 改造点)。P2——③ step 语义定为只前进不回退:仅新操作初始化 PREPARED,PENDING 认领保持,FAILED 重试/RUNNING 接管保留原 step 据以续跑(§9.2)。§14 增补:陈旧对账不覆盖新元数据、长 ALTER 与项目删除互斥、表级 cleanup 与 DROP DATABASE 不并发、DDL_APPLIED 下重试/接管不重置检查点。
- **v14(2026-07-18)**:按 Plan B 六轮复审(2 P1/2 P2)修订。P1——① 所有权取得补事务提交边界:所有权变更(INSERT/CAS)与表状态置位、step=PREPARED 构成短平台库事务,**必须在任何项目库副作用之前提交**;提交成功后锁内重读确认 `owner_token=newToken AND status='RUNNING'` 才执行项目库 DDL;提交失败或结果不确定不得产生项目库副作用(§9.2);② cleanup 认领后崩溃/失败无客户端重试者:清理调度器周期扫描并处置——到期 PENDING 认领、FAILED 由调度器充当重试者执行 FAILED CAS、Redis 锁已不属旧 token 的 RUNNING 走陈旧接管、SUCCESS 跳过;「陈旧 RUNNING 接管」条同步标注内部操作的重试者身份(§9.2)。P2——③ 内部指纹定义规范字节编码:带版本行式载荷(UTF-8、固定字段顺序、`\n` 分隔、deleteAfter ISO-8601 秒级)(§9.2);④ 文档头部状态行与实际评审轮次同步(改为「v1~v5 四轮整体评审 + v9~v14 六轮 Plan B 评审」表述)。§14 增补:所有权事务提交失败不执行 DDL、提交成功后崩溃可接管、PENDING 认领后崩溃自动接管、DROP 后终态提交前崩溃续跑、FAILED cleanup 自动重试。
- **v13(2026-07-18)**:按 Plan B 五轮复审(2 P1/1 P2)修订。P1——① 所有权建立顺序与 CAS 规则矛盾(v12 要求先验日志归属再产生副作用,但 FAILED/RUNNING 接管取锁后才换 token,新操作更无日志行可验):顺序统一为「Redis(newToken) → GET_LOCK → 再验 Redis → **原子取得日志所有权** → 再验日志归属 → 业务副作用」,新增「日志所有权取得」四分支条目——新操作 INSERT(RUNNING+token,唯一键冲突则锁内重读)、FAILED 重试 CAS、陈旧 RUNNING 接管 CAS、PENDING cleanup 认领 CAS(§9.2);② 预建 cleanup 无合法待调度状态(现有三态下提前置 RUNNING 会制造虚假陈旧执行者):`baas_ddl_log.status` 增加 **PENDING**(仅预建 cleanup 使用,owner_token 为 NULL),删除事务写 step=PREPARED/status=PENDING,到期后经认领 CAS(`WHERE status='PENDING' AND owner_token IS NULL`)转 RUNNING,未到期不认领、不得置 SUCCESS(§6.1/§9.2)。P2——③ cleanup-drop 无 HTTP 要素,内部操作单独定义指纹:SHA-256("INTERNAL"+"cleanup-drop"+projectId+表 ID+deleteAfter),预建时一次性生成,重试按持久化不可变字段复核(§9.2)。§14 增补:未到期 PENDING 不被认领、多实例并发认领仅一个成功。
- **v12(2026-07-18)**:按 Plan B 四轮复审(1 P0/2 P1/1 P2)修订。P0——cleanup-drop 仅凭表名定位会误删同名新表(A 清理 → 名称释放 → 用户重建同名 → B 陈旧任务取锁后按名 DROP;双层锁防并发不防顺序竞态):cleanup 的 operation_id 改为在 API 删除写 tombstone 的同一平台库事务中预生成落 `baas_ddl_log`(PREPARED,记录不可变表 ID),清理任务只认领预建记录,**取锁后按表 ID 重读元数据行,确认仍为 DELETED 且已到期方可 DROP**,行不存在/ID 不符/非 DELETED 一律作废(§6.1/§9.2)。P1——① fencing 只护日志不护副作用:加锁顺序固定为「Redis 锁 → GET_LOCK → 再次校验 Redis token 与日志 owner_token → 才产生副作用」,GET_LOCK 持有至平台元数据与日志终态提交后才释放(不再 DDL 结束即释放),平台元数据变更与日志检查点同一平台库事务、以 owner_token 条件更新作事务守卫(0 行整笔回滚)(§9.2);② FAILED 重试与 RUNNING fencing 条件矛盾:单独定义所有权转换 CAS(`SET owner_token=:new, status='RUNNING', retry_count=retry_count+1 WHERE id=:id AND owner_token=:observed AND status='FAILED'`),取双层锁后执行、成功才进入 RUNNING fencing(§9.2)。P2——统一入口顺序:鉴权+指纹 → 查日志 → 指纹冲突 409 → SUCCESS 不取锁直接返回快照 → 仅新操作/FAILED 重试/RUNNING 接管进锁并锁内重查/CAS;§8.3 同步标注 ACL 重放例外(§9.2/§8.3)。§14 增补:GET_LOCK 前停顿与元数据写入前停顿两个接管窗口、并发 FAILED 重试唯一成功、陈旧 cleanup 不误删同名新表、SUCCESS 重放不取锁。
- **v11(2026-07-18)**:按 Plan B 三轮复审(3 P1)修订。① Redis 锁不能隔离同一 operationId 的新旧执行者:锁 value 由 operationId 改为**每次执行唯一的 `owner_token`**(operationId 只作业务幂等键),续租/校验/释放全部原子比较 owner_token;新增 fencing 规则——`baas_ddl_log` 一切状态推进均为条件更新 `WHERE owner_token = ? AND status = 'RUNNING'`,丢锁旧执行者的续租/释放/日志写入全部落空(§9.2);② ACL PUT 不走 DDL 锁会破坏 owner 安全约束:改为**所有 ACL PUT 进入项目级双层锁**,与改表/删表/对账串行化,锁内重新校验表状态、列类型、可空性与实际索引(§8.3);③ 纯元数据 ACL 分支未兑现幂等契约:操作类型 acl-index 改为统一 **acl-config**,每次 ACL PUT 均记 `baas_ddl_log` 并支持指纹比对与快照重放(无 DDL 分支 PREPARED→METADATA_APPLIED,补索引分支含 DDL_APPLIED),重放不覆盖其间的其他 ACL 更新(§6.1/§8.3)。另将「GET_LOCK/DDL/RELEASE_LOCK 必须同一物理 Connection,不能沿用 Plan A 逐次 JdbcTemplate.execute」记入 §16.2 工程事实;§14 增补:接管后旧执行者隔离、ACL 与改表串行化、ACL 重放不覆盖新配置。
- **v10(2026-07-18)**:按 Plan B 二轮复审(5 P1/1 P2)修订。P1——① Redis 锁丢失不能中止执行中的 DDL:续租改为原子 compare-and-expire,并增加第二层防线——执行 DDL 的同一项目库连接上 `GET_LOCK('baas_ddl_{projectId}', 0)`,租约过期后的后继操作在数据库边界被拒,不与旧 DDL 并发(§9.2);② 进程崩溃遗留 RUNNING 无人接管:`baas_ddl_log` 增 `owner_token`,同 ID 同指纹重试在确认 Redis 锁失效且取得双层锁后 CAS 接管续跑(§6.1/§9.2);③ 删表不符合统一检查点模型:拆为「API 删除(无项目库 DDL,PREPARED→METADATA_APPLIED 即 SUCCESS,快照重放)」与「cleanup-drop 内部操作(服务端 UUID,清理任务取双层锁独立重试)」(§9.2);④ API 允许的注释/默认值长度超过 Plan A 元数据字段容量(实测 baas_table.comment/baas_column.comment/default_value 均 varchar(255)):Plan B 迁移脚本同步扩容为 varchar(2048)/varchar(1024)/text(§6.1);⑤ 幂等指纹改为「方法+路径+操作类型+规范化 body」(DELETE 无 body 也能区分目标表);废弃 `sys-aclidx` 确定性 ID(超 operation_id varchar(64) 且索引外部删除后无法重建),ACL PUT 改为携带客户端 operationId,补索引前探测项目库索引现状、不以历史 SUCCESS 判断(§7.3/§8.3/§9.2)。P2——⑥ 索引名生成规则:超 64 字符时截断列名 + 8 位稳定哈希后缀,不降低列名上限(§7.3)。§14 增补:陈旧 RUNNING 接管、watchdog 停顿超 TTL 的 DB 锁兜底、tombstone 重放与清理锁、超 255 字符双写、ACL 索引重建、64 字符列名索引生成。
- **v9(2026-07-18)**:按 Plan B 设计评审(2 P0/5 P1/1 P2)修订。P0——① DDL 字面量注入缺口:默认值改为类型化模型(JSON 标量按列类型解析后重新渲染规范字面量,禁止原始 SQL 表达式,text/json 列不支持默认值),客户端字符串(默认值/注释)一律不拼接 DDL 原文、注释限长转义,`ddl_text` 默认值占位符脱敏(§7.3/§6.1);② 删 owner 列越权窗口:改为单一 fail-closed 操作(同锁内关全部非 service_role ACL + 清 owner_column + DROP),取消 owner 配置同样强制同步关闭全部 anon/authenticated ACL(§7.3/§8.3)。P1——③ 承认跨库非同事务事实,引入检查点 PREPARED→DDL_APPLIED→METADATA_APPLIED、表状态机增 ALTERING、失败探测式重试(查 information_schema 判定 DDL 是否生效后续跑,不盲目重放)(§9.2/§9.5);④ `RENAME TO` 作为 ALTER 子句并入同一条语句,消除与其他操作混用时的部分成功(§7.3/§9.2);⑤ 锁改为 TTL 60 秒 + watchdog 每 20 秒续租,检查点推进前校验锁持有,DDL 专用超时 5 分钟与数据面 5 秒分离(§9.2/§13);⑥ 幂等键绑定请求内容:(project_id, operation_id) 联合唯一 + operation_type/table_name/request_hash/result_snapshot,hash 不一致 409,ACL 补索引用确定性服务端 ID,注明 Plan A 已建 `baas_ddl_log` 需 ALTER 迁移(§6.1/§9.2);⑦ 对账覆盖默认值/注释/主键/自增/唯一/索引,owner 约束破坏(列缺失/非 bigint/索引丢失)置 CONFLICT 阻断、不做普通修正(§9.4)。P2——⑧ 补 Studio 契约细节:operationId 位置(body/query)、建表 DTO 与响应快照、删表幂等重放、unique 天然含索引、索引命名规范与重命名列同步 RENAME INDEX(§7.3)。§14 同步增补测试项。
- **v8(2026-07-18)**:Plan B(表管理与 DDL)设计细化,经两轮逐节评审确认。① 改表定为**全能档**:加列、删列、改类型/长度、改可空/默认值/注释、单列唯一/索引增删、重命名列/表(§13);② 类型变更策略:维护无损转换集合,集合外一律要求 `allowLossy=true`,执行保持 MySQL 严格模式,数据不兼容 409 不静默截断(§13 兼容矩阵);③ 删列策略:带 `allowLossy` 确认后立即物理 DROP COLUMN,不做列级延迟清理(§9.5);④ 新增改表 PATCH 操作列表式契约:显式操作、同列单操作、`id` 列保护、owner_column 联动、表重命名约束(§7.3);⑤ 新增 §9.5 表状态机(CREATING/ACTIVE/FAILED/CONFLICT/DELETED,物理清理删除元数据行以复用名称);⑥ DDL 引擎细化:锁 key/过期/409 语义、幂等三态、多变更合并单条 ALTER(§9.2);⑦ ACL 配置面细则:纯元数据不走锁、owner 索引自动补建走 DDL 通道、取消 owner 不删索引(§8.3);⑧ 对账细化:六情形处理表、DDL 锁内执行、对账报告、定时默认关闭(§9.4);⑨ §14 增补 Plan B 必测项;§16.1 同步 A 已实现(PR #13)、B 设计定稿状态。
- **v7(2026-07-18)**:措辞澄清,无功能变更。§7.3 跨项目管理员的授权载体由示例措辞「专门 upms 角色(如 `ROLE_BAAS_ADMIN`)」改为与 §16.2 已确认工程事实一致的「upms 权限码 `baas_admin` authority 直查」——平台角色 authority 形如 `ROLE_<数字id>`,字符串角色不可用,原示例曾在 PR #13 code review 中引起误判。
- **v6(2026-07-17)**:新增 §16「实施规划与会话交接」——BaaS 核心 MVP 的 5 份实施计划拆分(A 底座/B 表管理/C 数据面/D Auth/E 前端)及依赖与状态、后续会话接手方法、计划编写标准、已确认工程事实清单;原修订记录顺延为 §17。规划性变更,不改动任何功能设计。
- **v5(2026-07-17)**:按第四轮评审修订。P0——JWT 紧急轮换改为独立状态转换:撤销全部 current 与 previous、生成新 current 且不保留 previous(原方案会让已泄露的 current 降级为 previous 继续被信任一个 access TTL);明确旧 access JWT 立即失效为预期代价、会话与 refresh token 不撤销、记高等级审计日志,§14 增加对应测试项。契约清理——表/用户管理路由改为集合/单资源标准形式,jwt-keys 补 emergency-rotate 端点(§7.3);API Key 摘要固定为 SHA-256,不再留 HMAC 选项(§12.1)。
- **v4(2026-07-17)**:按第三轮评审修订。P0——refresh reuse grace 与哈希存储的矛盾:采用同事务数据库方案,`_refresh_tokens` 增加 `consumed_at / replacement_token_id / reuse_grace_until / replay_payload_ciphertext`,首次刷新在行锁事务内轮换并保存 AES-GCM 加密的完整响应(AAD 绑定 project+session+token),grace 内重放解密返回同一响应,超窗撤销会话,grace 后清除密文;同步修正 7.2 接口摘要与 grace 规则的措辞冲突。P1——Studio 项目路由改为集合/单资源标准形式并补 PATCH(§7.3);anon 在 owner 表上的读写统一追加 `owner IS NULL`,开启 anon.insert 时校验 owner 列可空(§8.3);JWT sub 明确为 bigint 十进制字符串并严格解析(§7.2);JWT Key 增加紧急轮换语义(§6.1);密文格式落为 `v1:{keyId}:{base64(iv|ciphertext|tag)}` + AAD 约定,API Key 改为 SHA-256/HMAC 摘要 + 常量时间比较、不走 AES(§12.1);CORS 预检由 CORS Filter 在 ApiKeyAuthFilter 之前仅查元数据处理,`*` 时 allowCredentials=false(§12.2);项目创建先落 PROVISIONING 记录再产生外部副作用(§9.1)。
- **v3(2026-07-17)**:按复审意见修订,4 项安全 P0——① owner 策略补全写路径:角色 × 操作完整规则表(anon/authenticated 携带 owner 列一律 400,仅 service_role 可指定/修改 owner),owner 列强制 bigint + 单列索引(§8.3);② URL/apikey/JWT 三方项目一致性强制校验,不一致 401,校验通过前不选择数据源;secret key 与终端用户 JWT 互斥(§7.4);③ Studio 项目级对象授权:owner_user_id 归属校验、列表过滤、ROLE_BAAS_ADMIN 跨项目、project_ref 不作为授权凭据(§7.3);④ 密钥加密基线:BaaS 专用 AES-256-GCM 加密器,主密钥仅环境变量/Secret,fail-fast,密文带版本前缀,明确不继承默认 Jasypt(§12.1)。P1——refresh 并发 grace 窗口与事务行锁(§7.2)、JWT previous key valid_until 与轮换限制(§6.1)、对账跳过 tombstone/延迟 DROP 期间禁止重建同名(§9.3/9.4)、allowed_origins 数据模型与配置接口(§6.1/§7.3)、邮箱规范化与 bcrypt 长度边界(§7.2)、日志改结构化脱敏(§11)、安全场景测试清单(§14)。
- **v2(2026-07-17)**:按评审意见修订——① owner 列策略进 MVP(8.3);② 补完整会话模型:_sessions/_refresh_tokens、refresh 轮换、logout、改密撤销、JWT claims 约束(7.2);③ API Key 改 opaque publishable/secret + baas_api_key 哈希多 key 轮换,JWT 签名密钥独立并支持 kid 双版本(6.1、8.1);④ 补项目/DDL 状态机、操作日志、串行锁、幂等、删除阻断流程、对账边界(第 9 节);⑤ 拆分 Provisioner/Runtime 账号、凭据加密、隔离能力如实声明、专用 ProjectDataSourceRegistry(第 10 节);⑥ 明确 Cloud/Boot 双形态入口契约与 base_url 约定(第 5 节);⑦ P1:REST 语义细则、资源限制、表编辑器能力边界、管理面 API 契约、CORS/防暴力/审计、范围外清单扩充。
- **v1(2026-07-17)**:初稿,逐节确认通过。
