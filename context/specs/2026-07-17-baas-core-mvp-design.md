# BaaS 核心 MVP 设计(ai-work-baas)

- 日期:2026-07-17
- 状态:v39;v1~v5 经四轮整体评审定稿,v6 附实施规划与会话交接(§16),v8 补齐 Plan B(表管理与 DDL)设计细化,v9~v25 按 Plan B 十七轮设计评审修订,v26 为四维系统性自查修订,v27 为 Plan B 实施计划复审闭环修订,v28 补齐 Plan C(数据面 REST)设计细化,v29 按 Plan C 设计评审(2 P0/5 P1)修订,v30 按复审(1 P0/1 P1)修订,v31 按三轮复审(1 P1)修订,v32 补齐 Plan D(终端用户 Auth)设计细化(含终端用户软删/恢复与系统表 manifest v3),v33 按 Plan D 设计评审(1 P0/6 P1)修订,v34 按二轮评审(3 P1)修订,v35 按三轮评审(1 P1,发布协议补平台库迁移)修订,v36 按 Plan D 四轮评审(9 P1 甄别收敛)修订(修订记录补记),v37 补齐 Plan E(Studio 前端)设计细化,v38 按 Plan E 设计评审(codex,3 P2 甄别确认)修订,v39 按 Plan E 二轮评审(codex,3 P2 甄别确认)修订
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
| 终端用户注册 | MVP 仅邮箱 + 密码;不做邮箱验证,**注册即视为邮箱已确认**;不做密码找回。**注册即登录**:signup 成功即创建会话并返回与 login 同构的响应 |
| 终端用户删除 | Studio 软删(`_users.deleted_at`)+ **restore 恢复端点**;邮箱唯一键不释放(同邮箱重新 signup → 409),恢复后可重新登录、旧会话不复活 |
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

鉴权边界:`/data/**` 加入资源服务器 ignore-urls(平台 OAuth 放行),由 ApiKeyAuthFilter 强制校验;`/studio/**` 走平台登录态 + upms 权限,与现有服务一致。**仅 ignore-urls 不够**:它只是授权层 permitAll,`BearerTokenAuthenticationFilter` 仍会消费 `Authorization: Bearer <项目 JWT>` 送平台 opaque-token 内省并 401 短路(现有 `AiWorkBearerTokenExtractor` 仅 `skip-public-url=true` 时跳过公开路径,该配置默认 false;全局打开会改变 boot 单体全部 `@Inner`/ignore-urls 路径的既有语义,`aiworkBearerTokenExtractor` bean 又无 `@ConditionalOnMissingBean` 可覆盖,均不采用)。因此 common-security 增加独立配置 **`security.oauth2.client.skip-resolve-urls`**(默认空列表,存量服务行为不变):`AiWorkBearerTokenExtractor` 对匹配路径直接返回 null,token 根本不进入平台内省;Cloud(Nacos 种子)与 Boot(application.yml)均配 `[/data/**]`。

**入口落地清单(Plan C 细化,收口 Plan A/B 遗留 Follow-up)**:

- **网关路由**:`db/ai_work_config.sql` 的 `ai-work-gateway-dev.yml` 种子新增 `id: ai-work-baas / uri: lb://ai-work-baas / predicates: Path=/baas/**`(仿 upms 条目;AiWorkRequestGlobalFilter 全局剥外部路径第一段,已与本节路径表吻合,无需额外配置)
- **Nacos 配置种子**:新增 `ai-work-baas-dev.yml`(当前完全缺失,cloud 形态元数据源无配置、服务无法启动):元数据源(druid → `ai_work_baas`)、redis、`security.oauth2.client.ignore-urls: [/data/**]` 与 `skip-resolve-urls: [/data/**]`、`baas.provisioner.*`、`baas.project-db.*`
- **boot 形态元数据表并库(已确认决策)**:baas 平台元数据表在 boot 单体落 `ai_work` 单库(与 quartz/upms 同库惯例一致;baas 的 mapper 与 TransactionTemplate 全部绑定默认数据源,零代码改动)。脚本组织:`db/ai_work_baas.sql` 保持 cloud 形态单一事实源,`db/ai_work.sql` 增补相同的 baas 表 DDL(两种 compose 共用同一 mysql 镜像,`ai_work` 中多这组表对 cloud 形态无害),并以单测断言两个文件中 baas 表 DDL 逐语句一致防漂移
- **boot 配置**:`ai-work-boot/application.yml` 的 `security.oauth2.client.ignore-urls` 与 `skip-resolve-urls` 同步加 `/data/**`;`application-dev.yml` 补 `baas.provisioner.*` 与 `baas.project-db.*`;boot 冒烟测试补数据面路由断言
- **可信代理配置(Plan D,§12.2 限速的 Cloud 落点)**:`baas.auth.trusted-proxies` 默认空,**Cloud 形态必须显式注入**——当前 Nacos 种子与 compose 均无该属性,不配置则服务恒用网关的 remoteAddr,全部客户端共享同一 IP 计数器,任意来源的 30 次失败即连带封禁该项目全部登录。交付物:Nacos `ai-work-baas-dev.yml` 种子含该配置项;compose 为 gateway 服务在自定义网络上固定静态 IP,并以 **`/32`(或能覆盖网关出口的最小 CIDR)**注入 baas 服务,**不得信任整段 compose/内网网段**;同时 compose 不将 `ai-work-baas` 的 4010 端口映射到宿主——Cloud 形态数据面/auth 面一律经网关进入,禁止绕过网关直连。Boot 形态保持默认空列表(恒用 remoteAddr,§12.2)
- **compose**:新增 **`ai-work-baas/Dockerfile`**(仿 upms 范式;当前不存在,缺它 build context 无从构建);`docker-compose.yml` 新增 `ai-work-baas` 服务(build context + networks)。**Cloud 的 baas 服务与 Boot 单体同样受 `EnvMasterKeySource` fail-fast 约束,两份 compose 都必须注入 `BAAS_MASTER_KEYS`/`BAAS_ACTIVE_KEY_ID`**:统一以 `${BAAS_MASTER_KEYS:?BAAS_MASTER_KEYS 未配置}` 从宿主环境/部署 Secret 透传,真实密钥禁止写入 compose 文件与 Git。验证项:`docker compose config`(两份)、BaaS 镜像构建、readiness 启动冒烟(含缺主密钥时 fail-fast 断言)

## 6. 数据模型

### 6.1 平台元数据库(新建 `ai_work_baas` 库,独立 SQL 脚本)

| 表 | 内容 |
|---|---|
| `baas_project` | `project_ref`(短标识,入 URL,**不作为授权凭据**)、`db_name`、**状态机字段**(见 9.1)、`owner_user_id`(平台用户,Studio 归属校验依据)、`allowed_origins`(JSON,CORS 白名单)、runtime 账号名及**加密**凭据、**`ddl_fence_epoch`(BIGINT NOT NULL DEFAULT 0,项目级单调 fencing 计数,见 9.2;Plan B 迁移新增并按 DEFAULT 0 回填存量项目——Plan A 的 `createProject()` 不设置该列,靠默认值保证现有建项目路径无需改造即可插入)**、**`system_table_version`(INT NOT NULL DEFAULT 0,Plan D 迁移新增——迁移脚本与执行时点见 §9.1 v3 发布协议②;0 = 尚未确认为当前 manifest 版,新项目开通置 ACTIVE、存量迁移成功、后台扫描比对 MATCH_CURRENT 时写入当前版本号,见 §9.1 系统表版本准入)**。不存任何明文 key |
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
| `_users` | 终端用户:id(**固定 signed bigint 自增**,owner 列类型与其对齐,全链路以 Java Long 承载;**Plan A 现实现为 bigint unsigned,Plan B 迁移统一改为 signed**,含 `_sessions.user_id`、`_refresh_tokens.session_id/replacement_token_id` 等关联列与建表模板;存量项目按 §9.1 的 manifest 比对 + MIGRATING 检查点路径迁移)、email(trim + 小写规范化后唯一)、password_hash(bcrypt)、raw_meta(JSON)、**`deleted_at`(DATETIME NULL,软删标记,Plan D 新增列——系统表 manifest 随之升级为 v3,存量项目按 §9.1 迁移路径补列)**、时间戳 |
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
- `Prefer: return=representation` 时 POST/PATCH 返回行数据,默认仅返回主键/行数;**representation 是一次读取,anon/authenticated 使用它必须同时具备对应写权限(insert/update)与 select 权限**(§8.2,否则写通读禁的角色可借回读绕过 select ACL);权限检查在执行任何写入**之前**完成,不满足直接 403、事务不产生副作用;不带 representation 的 POST/PATCH 仍只要求对应写权限;service_role 照常绕过 ACL
- 分页:默认 `limit=100`,最大 `1000`;`Prefer: count=exact` 时响应头返回 `Content-Range: 0-19/87` 总数
- **值类型感知绑定与线协议矩阵(Plan C 细化)**:过滤值(URL 字符串)与 body 值(JSON token)按目标列逻辑类型严格解析后绑定,解析失败或越界一律 400、不静默舍入/截断;输入输出对称:

  | 逻辑类型 | 过滤值(URL 串) | body 接受的 JSON token | 校验/绑定 | 响应 JSON |
  |---|---|---|---|---|
  | `int` / `bigint` | 十进制整数串 | 整数 number(小数部分或字符串 → 400) | Long 绑定;int 超 32 位值域 400 | number(2^53 风险注明,§7.5) |
  | `decimal(p,s)` | 十进制数串 | number **或数字字符串**(允许调用方规避双精度损失) | BigDecimal 绑定;超 p/s 值域 400 | number(BigDecimal 无损序列化) |
  | `boolean` | `true` / `false` | JSON true/false(`0/1`、`"true"` → 400) | Boolean 绑定 | true/false(TINYINT(1) 的 0/1 按 §13 规范化层转回) |
  | `varchar(n)` / `text` | 原样字符串 | 字符串 | varchar 超 n 字符 400 | 字符串原样(XSS 清洗不介入,§7.5) |
  | `date` / `datetime` | `yyyy-MM-dd` / `yyyy-MM-dd HH:mm:ss` | 同格式字符串 | 严格解析(与 §7.3 默认值规范一致) | 同格式字符串输出 |
  | `json` | 仅 `is.null / is.not_null` | 任意合法 JSON 值(对象/数组/标量) | 序列化为规范 JSON 文本绑定 | **真实 JSON 值**(非转义字符串) |

- **NULL 与缺失字段语义(全类型统一)**:body **显式 `null`** → 绑定 SQL NULL(列 NOT NULL 则 400,不落到数据库层报错);**缺失字段** → 不进入 INSERT 列清单,由列默认值兜底(无默认值且 NOT NULL 由 MySQL 严格模式拒绝,映射 400)。`json` 列读出 SQL NULL 输出 JSON null;**不提供写入 JSON literal null 的方式**(显式 null 恒为 SQL NULL),两者的区分不进 MVP,静态 OpenAPI 注明
- **批量数组字段集合必须一致**:POST 数组各行的键集合不一致 → 400(单条 INSERT 列清单唯一,不做隐式补 DEFAULT/补 NULL)
- `like` 通配符:值中的 `%`/`_` 原样传递给 MySQL,**不做 PostgREST 的 `*`→`%` 转换**(差异在数据面静态 OpenAPI 中注明)
- `in` 值按逗号切分,不支持值内嵌逗号或引号转义(无法唯一切分即 400);空列表 `in.()` → 400
- `json` 列仅允许 `is.null / is.not_null` 过滤,其余操作符 400;`text` 列支持全部操作符
- `order` 支持逗号分隔多列,每列可选 `.asc` / `.desc`,缺省 asc;列名同样过元数据白名单
- **POST/PATCH body 含 `id` 列一律 400**(所有角色,含 service_role):主键统一服务端自增生成、禁止显式指定与更新
- 默认响应体:POST 201 + 插入行主键数组 `[{"id": 1}, …]`(JDBC generated keys);PATCH/DELETE 200 + `{"count": n}`;`Prefer: return=representation` 时 POST/PATCH 返回完整行数据(DELETE 不支持 representation)
- **representation 事务算法(Plan C 细化)**:MySQL 无通用 `UPDATE ... RETURNING`,且「更新后按原过滤条件重查」不可用(修改过滤列自身会漏行、并发新匹配行会被误纳入)。带 `return=representation` 的 PATCH 在单连接事务内固定三步:① `SELECT id ... WHERE {owner 策略 + 过滤条件} FOR UPDATE` 捕获目标主键;② 按捕获主键 UPDATE(`count` = 捕获行数);③ 按捕获主键 SELECT 组装 representation,随后提交。POST 的 representation 同样在插入事务提交前按 generated keys 回查完整行。默认形态(不带 representation)的 PATCH/DELETE 仍直接按过滤条件执行单条 UPDATE/DELETE。**representation 行数上限 1000**(与批量插入/limit 上限一致):PATCH 捕获阶段以 `LIMIT 1001` 探测,超限 400 + hint 提示缩小过滤范围(不带 representation 时不设行数上限)
- `Prefer: count=exact` 以同过滤条件执行第二条 `COUNT(*)` 后拼 `Content-Range`;未携带该头不执行 count
- bigint 列(含 `id` 与 owner 列)在响应 JSON 中以**原样 number** 输出(与 PostgREST 行为对齐;JS 2^53 精度风险在静态 OpenAPI 注明),不沿用平台 Long→String 全局定制(见 §7.5)
- 关联/嵌套查询、OR、聚合不进 MVP

### 7.2 Auth API(终端用户,完整会话模型)

```
POST /auth/v1/signup                          邮箱+密码注册(即视为邮箱已确认);注册即登录:创建会话并返回与 login 同构的响应
POST /auth/v1/token?grant_type=password       登录:签发 access JWT + refresh token,落 _sessions
POST /auth/v1/token?grant_type=refresh_token  刷新:refresh token 一次性轮换,超出 reuse grace 后复用才撤销整个会话
POST /auth/v1/logout                          注销:撤销当前会话及其 refresh token
GET  /auth/v1/user                            凭 access JWT 取当前用户
PUT  /auth/v1/user/password                   修改密码:body 携带当前密码与新密码,成功后撤销该用户全部会话
```

- **access JWT**:HS256(项目当前 `baas_jwt_key` 签名,带 `kid`),TTL 1 小时,claims 固定为 `iss=baas/{project_ref}`、`aud={project_ref}`、`sub={user_id}`(**bigint 用户 ID 的十进制字符串**,验签后严格解析为 bigint 再绑定 SQL,解析失败 401)、`role=authenticated`、`session_id`(**`_sessions.id` 的 JSON number**,验签严格解析为 Long,失败 401,见 §7.5/§7.6)、`iat`、`exp`。验签接受 current 及未过 `valid_until` 的 previous kid
- **refresh token**:不透明随机串,哈希落 `_refresh_tokens`,TTL 30 天,一次性使用、每次刷新轮换
- **并发刷新语义**(同事务数据库方案):首次刷新在**行锁事务**内完成——创建子 token、父 token 标记 `consumed_at` 并写入 `replacement_token_id` 与 `reuse_grace_until`(+10 秒),同时把完整刷新响应经 AES-GCM 加密存入 `replay_payload_ciphertext`(AAD 绑定 `project_id + session_id + token_id`,防密文跨记录替换)。grace 窗口内重放同一旧 token:解密并返回**同一响应**(幂等,容忍多标签/网络重试);**超窗重放判定为泄露,撤销整个会话**;grace 结束后清除密文(惰性 + 定时)
- 会话撤销以 refresh token 为准;access JWT 短 TTL 自然过期,MVP 不做 access token 黑名单
- **注册/登录细则**:邮箱 trim + 小写规范化;密码长度 8–72 字节(bcrypt 只取前 72 字节,超长直接 400 拒绝,避免截断歧义);bcrypt cost 10
- **鉴权规则(Plan D 细化)**:`/auth/v1/*` 全部端点**强制携带 publishable key**(§7.4 的 apikey 头);**secret key 调用 auth 端点一律 403**(管理面能力已由 `/studio` 提供,不做身份混合;§7.4 secret+JWT 互斥规则下 secret key 本也无法调用需 JWT 的端点)。signup/login/refresh 在 anon 上下文下调用;logout/`GET user`/`PUT user/password` 额外要求有效 access JWT,缺失或无效 → 401
- **响应形态(Plan D 细化)**:signup/login/refresh 三者响应同构 `{access_token, token_type: "bearer", expires_in, refresh_token, user: {id, email, createTime}}`;login 失败(邮箱不存在/密码错误/用户已软删)统一 401「邮箱或密码错误」,不泄露邮箱注册状态;signup 邮箱已被占用(含软删用户)→ 409;logout 与改密成功 → 204;logout 幂等(会话已撤销时重复调用仍 204)
- **改密细则(Plan D 细化)**:body `{currentPassword, newPassword}`,**两者均按 8–72 字节校验**(同注册/登录口径,避免 bcrypt 截断歧义;合法存量密码必在此区间,超界 currentPassword 不可能匹配,提前 400);软删用户 → 401(§7.3 账户管理裁定);先验 currentPassword(bcrypt 比对),失败 → 401 并计入 §12.2 防暴力计数(access JWT 可能被窃取,旧密码校验阻止窃取者借改密永久接管账户;MVP 无密码找回,误改无法挽救);成功后撤销该用户**全部**会话(含当前会话,需重新登录)
- **会话撤销语义(统一定义,logout/改密/软删/refresh 超窗复用)**:`_sessions.status` 置 REVOKED,同一项目库事务内将该会话全部未消费 refresh token 置为已消费;refresh 校验时联查所属会话必须为 ACTIVE
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
/studio/projects/{ref}/users               GET 终端用户列表(分页,含软删状态)
/studio/projects/{ref}/users/{userId}      DELETE 软删终端用户
/studio/projects/{ref}/users/{userId}/restore POST 恢复软删终端用户
/studio/projects/{ref}/reconcile           POST 触发表结构对账
/studio/projects/{ref}/system-tables/migrate POST 管理员手动触发系统表迁移
```

管理面沿用平台 `R<T>` 响应与 springdoc 文档;数据面提供**静态** OpenAPI(描述查询语法契约,不做 per-project 动态反射)。项目 CORS 白名单(`allowed_origins`)通过 `PATCH /studio/projects/{ref}` 配置。

**终端用户管理细则(Plan D)**:权限沿用项目归属校验(owner 或 `baas_admin`,同其余 Studio 端点)。DELETE = **软删**:置 `_users.deleted_at` 为当前时间,并在同一项目库事务内按 §7.2 会话撤销语义撤销该用户全部会话;软删用户 login 拒绝(统一 401)、邮箱唯一键不释放(同邮箱重新 signup → 409);已软删用户重复 DELETE 幂等成功。restore:`deleted_at` 置 NULL,恢复后可重新登录,**旧会话不复活**;未软删用户 restore 幂等成功。软删与恢复均入 `baas_audit_log`。**审计跨库语义**:业务 DML 在项目库、`baas_audit_log` 在平台库,不可能同事务(§9.2 同款跨库前提);MVP 定为**项目库事务提交成功后写审计,审计 best-effort**——审计写入失败不回滚业务、不改变已确定的业务结果,记结构化 error 日志(项目、目标用户、操作类型、失败原因)供人工补录,不引入审计 outbox/重试队列(内部 Alpha 复杂度不值,二期有合规要求再升级);审计必须在业务事务**提交之后**写入,禁止先审计后业务(避免「有审计、无操作」的虚假记录)。软删用户已签发的 access JWT 在 TTL 内**仍可访问数据面 `/rest`**(数据面不回查 `_users`;即时失效依赖 §6.1 紧急轮换);但**账户管理端点回查软删状态**——`GET /auth/v1/user` 与 `PUT /auth/v1/user/password` 对软删用户返回 401(最小权限,软删账户不得继续做账户管理),`POST /auth/v1/logout` 仍幂等 204(撤销会话无害)。静态 OpenAPI 注明。这两个端点仅操作 `_users`/`_sessions`/`_refresh_tokens` 行数据(DML),不取 §9.2 DDL 锁;执行前须通过 §9.1 **系统表版本准入**(未迁移至 v3 的项目 fail-closed 返回明确错误,不得以缺列 500 暴露)。

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
- **owner 列索引不变量(锁内按修改后的最终结构校验)**:当前 owner 列在改表后必须仍保留单列普通索引或单列唯一索引(**仅满足 §9.4 单列索引谓词的索引计为有效**)——`modifyColumns` 将 owner 列改为 `unique=false, indexed=false` → 400(否则 DDL 引擎会合法生成 DROP INDEX,产出 ACTIVE 但违反 owner 不变量的表,§9.4 只能事后判 CONFLICT);`unique=true → unique=false, indexed=true` 在**同一条 ALTER** 中以普通索引替换唯一索引;确需删除 owner 索引,必须先经 ACL PUT 取消 owner(触发 §8.3 fail-closed 关闭 ACL),再以独立 ALTER 删除
- **索引操作按实际名定位**:新建索引使用规范名;**删除、替换、重命名已有索引前,必须在锁内查询项目库 `information_schema.statistics`,按该列实际存在的单列索引名生成 DDL**——对账导入的表可能带非规范索引名(如 `foo_email`),按规范名 `idx_email` 生成 DROP/RENAME 会失败

**项目级对象授权(防 IDOR)**:upms 菜单/API 权限只解决「能否进入 Studio 功能」,不能替代项目归属检查——

- 项目列表仅返回 `owner_user_id = 当前平台用户` 的项目
- 所有 `/studio/projects/{ref}/**` 操作(详情、表、Key、用户、删除、对账)一律校验项目归属,不匹配返回 404(不泄露存在性)
- 仅持有专门 upms 权限码 `baas_admin` 的超级管理员可跨项目操作(以 authority 字符串直查实现;平台角色 authority 形如 `ROLE_<数字id>`,不存在字符串角色,见 §16.2 工程事实)
- `project_ref` 只是路由标识,**不作为授权凭据**
- **系统表手动迁移契约**:`POST /studio/projects/{ref}/system-tables/migrate` 仅 `baas_admin` 可调用,非管理员统一 404 不泄露项目存在性;请求不携带 operationId(迁移以项目状态 + 版本化 manifest + 逐表 epoch 检查点幂等),锁忙返回 409;仅 ACTIVE/FAILED 且 manifest 精确匹配已知 legacy 版本时执行,当前版返回 `{status:"ACTIVE", migrated:false}`,迁移成功返回 `{status:"ACTIVE", migrated:true}`,结构不属于当前版或已知 legacy 版返回 409 且不执行 ALTER,越界或执行失败按 §9.1 置 FAILED 并返回 409。路由同步执行至本轮迁移完成或明确失败,不返回“已受理”式假成功

### 7.4 请求身份三态

数据面请求头带 `apikey`(publishable key 或 secret key,opaque,服务端查 `baas_api_key` 哈希解析出项目与基础角色),终端用户再叠加 `Authorization: Bearer <access JWT>`:

- `anon`:仅 publishable key,受表级 ACL 约束
- `authenticated`:publishable key + 有效 JWT,受表级 ACL + owner 列策略约束
- `service_role`:secret key,绕过 ACL 与 owner 策略,仅服务端持有,Studio 默认遮挡显示

**三方项目一致性(强制)**:URL 中的 `{projectRef}`、API Key 所属项目、JWT 的 `iss/aud` 三者必须指向同一项目,**任一不一致一律 401**;一致性校验完成之前不选择数据源、不建立任何项目库连接。校验顺序:apikey 解析项目 → 与 URL projectRef 比对 → 若带 JWT 再校验 iss/aud 与 kid。

**secret key 与 JWT 互斥**:secret key 恒定解析为 `service_role`;secret key 请求**同时携带终端用户 JWT 直接 401 拒绝**,不做身份混合。

### 7.5 数据面执行架构(Plan C)

- **包与序列化隔离**:数据面代码位于独立包 `com.aiwork.baas.data`,配独立 `@RestControllerAdvice` 输出 §11 错误体(既有 `BaasStudioExceptionHandler` 限定 `com.aiwork.baas.controller` 包,不覆盖数据面);请求与响应使用数据面**独立 ObjectMapper**(专用 HttpMessageConverter 仅挂数据面控制器),绕开平台全局 Long→String、时间格式定制与 **XSS 反序列化清洗**(后者会静默改写用户写入的数据,数据面必须绕开)
- **请求管道**(servlet filter,仅匹配 `/data/**`):`CorsFilter`(§12.2)→ `ApiKeyAuthFilter` → controller。鉴权结果(项目实体、角色、终端用户 ID)放入 request attribute 承载的 DataRequestContext,**不进 Spring SecurityContext**(数据面与平台安全体系零耦合)
- **ApiKeyAuthFilter** 按 §7.4 顺序执行:`apikey` 头 SHA-256 后查 `baas_api_key`(常量时间比较)→ 与 URL projectRef 比对 → 项目状态非 ACTIVE 一律 403 → 若带 JWT 再验;一致性校验完成前不触碰 ProjectDataSourceRegistry。`last_used_time` 更新为**节流 best-effort**(每 key 每分钟至多写一次,失败不影响请求)
- **JWT 验签完整实现属 Plan C**(Plan D 仅签发、会话模型与轮换端点):按 kid 查 `baas_jwt_key`(CURRENT,或 PREVIOUS 且未过 `valid_until`;REVOKED 拒绝),`BaasCryptoService` 解密 HS256 secret 验签。**验签逐项清单(缺一即 401,实现不得只做子集)**:① JWS header `alg` 必须恰为 `HS256`(`none`/`HS384` 等一律拒绝,不以 token 自带 alg 选择算法);② 必需 claim 固定为 `iss/aud/sub/role/session_id/iat/exp`,任一缺失拒绝;③ `iss/aud/role` 严格匹配(§7.4 三方一致性),`sub` 与 `session_id` 均严格解析为 Long(§7.2;session_id 严格解析为 Plan D 收紧——Plan C 已合入实现仅校验存在,改造点见 §7.6/§16.2);④ `exp` 未过期且 `iat` 不在未来,时钟偏差统一容忍 60 秒;⑤ `exp − iat ≤ 1 小时`(§7.2 签发 TTL 上限,超长 TTL token 拒绝)。`session_id` 严格解析后仅透传,数据面**不回查** `_sessions` 存活性(access JWT 在 TTL 内不因 logout 提前失效,即时失效依赖 §6.1 紧急轮换)。**签名密钥不做任何缓存、每请求直查**——§6.1 紧急轮换「旧 access JWT 立即失效」与 §14 对应必测项依赖此
- **元数据每请求直查、MVP 不做缓存**:每请求约 5-6 次平台库点查(key/project/jwt key/table/columns/acl,均走主键或唯一索引)。DDL 可能发生在其他实例,进程内缓存必须解决跨实例失效,内部 Alpha 不值得;缓存留二期,届时仅替换此层实现
- **DDL 执行屏障(消除直查与 ALTER 的 TOCTOU)**:仅「直查 + ALTERING 阻断」不构成强一致——请求可能读到 ACTIVE 与旧列结构后并发 ALTER 完成,再以旧 AST/绑定类型执行 SQL。因此所有 `/rest/v1/{table}` 请求(含 GET)统一在**项目库事务**内按固定顺序执行:① 锁外先用元数据完成解析、白名单校验与 §9.5 快速阻断(非竞态路径不进项目库);② 开启事务后以 `SELECT 1 FROM \`{table}\` WHERE FALSE` 取得目标表**共享 MDL**(MySQL 表级元数据锁随事务持有到提交,跨实例天然成立:未完成的 ALTER 在 MDL 排他升级处阻塞,已完成的 ALTER 则不可能再让旧结构被读到);③ MDL 取得后**重新读取**平台元数据(表状态 + 列结构),ALTERING/CONFLICT 等按 §9.5 阻断,否则以重读结果构建并执行 SQL;④ 事务提交/回滚释放 MDL。预锁语句因表被并发重命名/删除失败时,重新解析一次元数据后按 §9.5 响应(404/403);数据面持有 MDL 的时长受 5 秒 queryTimeout(§13)约束,DDL 的 5 分钟超时窗口足以等待,MDL 等待超时按 §11 超时路径返回
- **SQL 构建两级**:QueryParser(查询串 → 过滤 AST + select/order/分页)与 SqlBuilder(AST + 元数据 + 角色上下文 → 参数化 SQL);拼入 SQL 原文的只有白名单校验后反引号包裹的列名,值全部占位符绑定;owner 策略(§8.3)在 SqlBuilder 内注入(SELECT/UPDATE/DELETE 追加过滤、INSERT 强制覆写)
- **执行层**:`ProjectDataSourceRegistry.execute(project, ds → …)` 借用连接池,JdbcTemplate 设置 `queryTimeout = 5s`(§13;注册表本身未设,须在此层补);所有数据请求按上条执行屏障在单个 Connection 上手动事务执行(`setAutoCommit(false)`,成功 commit、失败 rollback),同一事务同时兑现 §7.1 批量插入单事务原子与 representation 三步算法;**查询结果禁止全量物化**,且仅靠 RowCallbackHandler 不够——Connector/J 默认把完整 ResultSet 先读进内存,逐行回调只是遍历已物化的结果。真流式钉死为:注册表 JDBC URL 追加 **`useCursorFetch=true`**(服务端游标;Plan A 现 URL 无此参数,Plan C 补),数据面查询语句统一 `TYPE_FORWARD_ONLY + CONCUR_READ_ONLY` 并设置 **`fetchSize=100`**(不用 `fetchSize=Integer.MIN_VALUE` 的客户端流式:中途 413 中止时它必须排空剩余行,服务端游标可干净提前关闭)。逐行写入有界序列化缓冲并累计字节数,超 §13 响应体上限即中止——响应未提交则 413,representation 场景同时回滚事务(JDBC queryTimeout 只管 SQL 执行,不覆盖结果集物化与序列化阶段);**并发响应构建信号量**(§13,默认 8):进入序列化阶段前获取许可、`finally` 中必然释放,无许可立即 429——否则每请求 16 MiB 缓冲 × 全局 200 连接预算理论可放大至 3.2 GiB,信号量把响应缓冲总量钉在 8 × 16 MiB(临时文件落盘方案复杂度不值内部 Alpha,不采用)
- **表状态阻断响应码**(§9.5 执行面):元数据无此表或 DELETED(tombstone)→ 404(对外等同不存在,不泄露);CREATING/ALTERING/FAILED/CONFLICT → 403 + hint 说明表暂不可用
- **静态 OpenAPI**:手写 `baas-data-api.yaml` 入库(`ai-work-baas` 资源目录),以 base_url 相对路径描述(§5),覆盖查询语法、鉴权头、Prefer 语义、错误体与全部状态码;MVP 仅作为仓库交付物,不新增运行时端点(数据面路径全部在 apikey 之后,不为文档开匿名面)

### 7.6 终端用户 Auth 执行架构(Plan D)

- **管道与包复用**:auth 端点 controller 位于数据面包体系(`com.aiwork.baas.data`),完整复用 Plan C 请求管道(`CorsFilter` → `ApiKeyAuthFilter` → controller)、数据面独立 ObjectMapper 与独立异常出口(§11 错误体);`ApiKeyAuthFilter` 仅新增一条规则:**路径为 `/auth/v1/**` 且 key 为 secret → 403**(其余鉴权顺序与三方一致性逻辑不变)。需要 JWT 的端点(logout/`GET user`/`PUT user/password`)在 controller 层要求上下文角色为 authenticated(anon → 401);全部 auth 端点在执行任何项目库 SQL 前须通过 §9.1 **系统表版本准入**(未迁移至 v3 → 403 + hint,fail-closed)
- **项目库访问**:`_users`/`_sessions`/`_refresh_tokens` 的全部操作经 `ProjectDataSourceRegistry.execute` 借用连接,单连接手动事务,`queryTimeout` 5 秒(同 §13 数据面口径);auth 端点不涉及用户表动态 SQL,不需要 §7.5 的 MDL 屏障与响应信号量(响应体恒小)
- **refresh 行锁事务四分支**(落地 §7.2 并发语义):按 `token_hash` `SELECT ... FOR UPDATE` → ① 未消费且未过期且会话 ACTIVE → 轮换(创建子 token、标记 consumed、写 grace 与加密重放载荷);② 已消费且在 `reuse_grace_until` 内 → 解密 `replay_payload_ciphertext` 返回同一响应;③ 已消费且超窗 → 判定泄露,撤销整个会话(§7.2 撤销语义)后 401;④ 过期/不存在/会话非 ACTIVE → 401
- **会话上下文与 session_id 线协议(Plan D 收紧并改造 Plan C 已合入代码)**:`session_id` claim 钉死为 `_sessions.id` 的 JSON number,验签升级为**严格解析为 Long**(类型不符/越界 401,与 `sub` 同款,见 §7.5 清单③)。实测 Plan C 实现仅校验该 claim 存在、`VerifiedEndUser.sessionId` 为宽松 `String.valueOf`,且 `ApiKeyAuthFilter` 组装 `DataRequestContext` 时丢弃了 sessionId(§16.2)——Plan D 须:① 验签改严格 Long 解析;② `VerifiedEndUser.sessionId` 改 Long;③ `DataRequestContext` 增加 sessionId 字段。logout 从请求上下文取 session_id,**仅撤销该会话**(§7.2 撤销语义作用于当前单会话),同用户其他会话不受影响
- **JWT 签发器(`BaasJwtSigner`)**:每次签发直查该项目 CURRENT `baas_jwt_key`(**不缓存**,与 Plan C 验签的「密钥无缓存直查」对称,共同支撑 §6.1 紧急轮换立即生效)→ `BaasCryptoService` 解密 HS256 secret → 按 §7.2 固定 claims 签发(header 携带 kid)
- **密码哈希**:spring-security-crypto 的 `BCryptPasswordEncoder`(cost 10,经 ai-work-common-security 传递依赖已在 classpath);超 72 字节在参数校验层前置 400(§7.2)
- **JWT 轮换端点事务**(落地 §6.1 既定语义):轮换在平台库事务内对该项目全部 `baas_jwt_key` 行 `SELECT ... FOR UPDATE`(防并发轮换产生双 CURRENT):常规轮换——存在未过 `valid_until` 的 PREVIOUS → 409;否则 CURRENT → PREVIOUS(`valid_until` = now + access TTL)并生成新 CURRENT。紧急轮换——全部 CURRENT/PREVIOUS → REVOKED,生成新 CURRENT,不保留 previous;记高等级审计日志
- **grace 密文与过期 token 清理**:惰性(refresh 链路命中超窗记录时顺带清除密文)+ 定时任务(遍历 ACTIVE 项目):① 清除 `reuse_grace_until` 已过的 `replay_payload_ciphertext`(**只清密文列,不删行**);② 物理删除**仅限 `expire_time` 已过期**的 refresh token 行(无论是否已消费),防 `_refresh_tokens` 无限增长。**已消费但未过期的行必须保留至原 `expire_time`**——若超窗即物理删除,旧 token 再次出现会落入 refresh 四分支的「不存在 → 401」,§7.2「超窗重放判定为泄露、撤销整个会话」的检测就此失效;过期后旧 token 重放本就 401 兜底,届时删行不损失检测能力。任务仅 DML、逐项目 best-effort,不取 DDL 锁,单项目失败不影响其余项目

### 7.7 Studio 前端(Plan E)

ai-work-ui 中的 BaaS 控制台,§7.3 管理面的界面化。**纯前端 + 菜单种子交付,不改后端任何代码**——联调中发现的后端契约问题单独立项修复,不混入 Plan E。

**范围(准全量,经需求方确认)**:项目列表/创建/详情、可视化表结构编辑器(建/改/删表)、表级 ACL 与 owner 配置、API Key 管理(明文仅显示一次)、终端用户管理(列表/软删/恢复)、JWT 常规/紧急轮换、手动对账触发、`allowed_origins` 配置。**明确不含**:表数据浏览(严格按 §7.3 界面化,数据面 `/rest` 不接入,留 MVP 后迭代);`system-tables/migrate` 入口(baas_admin 专用运维,走 curl)。

**信息架构与路由**:

- 侧边栏一级菜单「BaaS」由 upms `sys_menu` 下发:菜单 `path=/baas/projects` 按 ai-work-ui 既有约定自动映射 `src/views/baas/projects/index.vue`,不改路由注册机制;交付物含 `db/ai_work.sql` 的 `sys_menu` + `sys_role_menu` 种子(存量环境的 INSERT 语句随 PR 说明给出)
- 项目列表页:members 页三件套范式(搜索卡 + 表格卡 + 创建弹窗);状态列覆盖 §9.1 全部项目状态,浅底深字标签;CREATING → ACTIVE 由用户手动刷新,MVP 不做轮询
- 项目详情页 `/baas/projects/:ref` 为前端**静态子路由**(动态参数路由不进菜单,侧边栏高亮归属「BaaS」菜单项)。现 `AppSidebar.isActive` 为精确匹配(`route.path === item.path`),详情子路由不会命中父菜单,**须改为边界安全的前缀匹配**(`route.path === item.path || route.path.startsWith(item.path + '/')`——以 `/` 边界避免 `/baas/projects` 误命中同前缀平级菜单),纳入 §14 路由/侧边栏回归;页内 Tabs——**概览 | 表 | API Keys | 用户**,当前 tab 入 URL query(如 `?tab=tables`)可直达
- 概览 tab:项目基本信息 + `allowed_origins` 编辑(**通配/白名单二态显式切换,不可退化为纯 tag 输入**:「允许全部来源(`*`)」开关开启时置灰 tag 输入并提交 `allowedOrigins: null`——后端 §12.2 `parseAllowedOrigins` 仅 null 为通配;关闭开关则编辑动态 tag 列表提交字符串数组,**空列表 = `[]` = 拒绝全部浏览器来源**,与 null 语义相反不可混同;后端 `ProjectVO` 对 null 与 `[]` 分别回显 `null` 与空数组,前端据此还原开关初态,防止默认通配项目被 tag 编辑器静默存成 deny-all)、PATCH 提交;危险操作区含常规/紧急 JWT 轮换、删除项目、手动触发对账(对账展示返回报告摘要)

**接入层**:新增 `src/api/baas/{project,table,apiKey,endUser}.ts`,沿用 `utils/request.ts` 统一拦截(`R<T>` 解包、token 注入、401 跳转);新增 env `VITE_BAAS_PATH` 默认 `/baas`(cloud 形态走网关剥首段),boot 单体部署覆盖为 `/admin`(同 `VITE_AUTH_PATH` 先例)。

**表结构编辑器(全屏抽屉 ~70% 宽,建/改共用,改表预填现有结构)**:

- 列定义可编辑表格(§7.3 的 9 字段列定义对象),类型联动:varchar 显示长度(1~4096)、decimal 显示精度/标度、text/json 禁默认值、datetime 默认值支持 `CURRENT_TIMESTAMP` 特例;`id` 主键行只读置灰,**且为纯 UI 展示行——建表提交时一律剔除,不进 `columns`**(后端 `TableManagementService.toColumnPlan` 对名为 `id` 的列定义直接 400,该主键由服务端自建);改表同理不对 id 行记录任何操作
- **改表以显式操作意图跟踪,不做 diff 推导**:每行的加/删/改/重命名动作记录为操作列表,提交时组装为 §7.3 契约的 `addColumns/dropColumns/modifyColumns/renameColumns`(重命名 ≠ 删+加,必须显式);同列多操作在前端即拦截;无任何操作时禁提交
- **allowLossy 二次确认协议**:`dropColumns` 前端已知有损 → 提交前先行确认;有损 `modifyColumns` 由后端裁决——首次以 `allowLossy=false` 提交,收到「需要 allowLossy」的 400 后弹确认框展示后端风险说明,确认后以**同一 operationId** 重发 `allowLossy=true`。前端**不复刻 §13 类型兼容矩阵**,后端是唯一裁决者
- 表级 ACL 配置:anon/authenticated × select/insert/update/delete 开关矩阵 + `owner_column` 下拉(仅 bigint 列可选);后端约束(anon.insert 要求可空、owner 自动建索引等)违反时直显后端 msg

**危险操作确认分级**:紧急轮换(全部终端用户 token 立即失效)、删除项目须**输入项目 ref** 方可提交;删表须**输入表名**(文案说明软删 tombstone、同名禁重建);常规轮换、Key 吊销、用户软删/恢复用 confirm,文案说明后果(软删撤销全部会话且邮箱不释放、restore 后旧会话不复活)。

**API Key 展示**:创建成功弹窗展示明文 + 复制按钮,醒目提示「关闭后不可再查看」——**独立创建的 key,以及建项目响应随附的初始 publishable key 与 secret key,均在各自创建响应中一次性展示明文并可复制**(后端 `CreatedKeyVO.plaintext` 与 `CreatedProjectVO.{publishableKey, secretKey}` 均仅返回一次、库中只存哈希,`ApiKeyVO` 后续仅回显前缀,不显即永久丢失、密钥不可用——**建项目弹窗须同时展示两枚初始 key**);「secret key 恒遮挡、不提供回显」(§7.4)仅约束**列表与后续查看**,不适用于创建响应本身。

**横切契约**:operationId 由前端 `crypto.randomUUID()` 生成,同一次用户意图的重试**复用同一 ID**(幂等重放拿原快照);**409 须按后端 msg 区分,不可统一「刷新重试」**——仅 DDL 锁忙(§9.2「该项目有 DDL 操作进行中」)提示「操作冲突或锁忙,请刷新后重试」;指纹不一致(§7.3)、有损 ALTER 数据不兼容/可空→非空含 NULL(§13)、数据面唯一键冲突(§11)等**非锁 409 一律直显后端 msg**(这些场景「刷新重试」无效、会把用户带入重试死循环);其余错误经统一拦截直显后端 msg。

**测试与门禁**:复杂逻辑抽 `.ts` 纯函数 + Vitest 单测(沿用 ai-work-ui 既有范式,页面组件不做快照测试),必测项见 §14;`npm run lint`、`npm run build`(vue-tsc)、`npm run test:unit -- run` 三门禁全绿;UI 视觉遵循 ai-work-ui/DESIGN.md。

## 8. 权限模型

### 8.1 API Key

opaque key(如 `pub_` / `sec_` 前缀 + 随机串),创建时仅展示一次明文,库中只存哈希与展示前缀;每项目每类型可同时存在多个有效 key,轮换流程 = 创建新 key → 切换调用方 → 吊销旧 key,无停机。JWT 签名密钥与 API Key 完全分离(6.1)。

### 8.2 表级 ACL

`baas_table_acl` 控制 anon / authenticated 对每表的 select/insert/update/delete;**新表默认全部关闭**,需显式开启。四项权限相互独立;唯一的组合规则:`Prefer: return=representation` 的 POST/PATCH 额外要求 select 权限(§7.1,防止写权限旁路读取),检查前置于写入执行。

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
- **每次 ACL PUT 都以操作类型 `acl-config` 记入 `baas_ddl_log`**,幂等键即客户端 `operationId`,遵守 §7.3 统一幂等契约(指纹比对、`result_snapshot` 重放——重放只返回原快照,不覆盖其间发生的其他 ACL 更新)。无需 DDL 的分支走 PREPARED → METADATA_APPLIED(即 SUCCESS),**不改表状态**;需补索引时同一操作经 PREPARED → DDL_APPLIED → METADATA_APPLIED,**与改表一致在所有权事务中置表 ALTERING、完成回 ACTIVE**(数据面阻断窗口语义与 §9.5 改表相同)
- 设置 `ownerColumn` 时**在锁内重新校验**:表状态为 ACTIVE、列存在、类型 bigint、`anon.insert=true` 时列必须可空;**`ownerColumn` 不得是 `id`、主键列或自增列**(按实际结构校验,MVP 即 `ownerColumn != "id"`;违反返回 400,且不修改 ACL 或索引——归属标识与记录主键合并后,authenticated 插入会把主键强制写成 `jwt.sub` 导致主键冲突,anon 插入的自增主键必然非 NULL、`owner IS NULL` 再也查不到自己刚插入的行);**探测项目库索引现状**,该列尚无满足 §9.4 单列索引谓词的有效索引(含唯一索引)则自动补建——是否补索引由现状决定,不以历史 SUCCESS 记录判断(否则「owner 取消 → 索引被删 → 再次设置 owner」场景无法重建索引)
- **取消 owner 配置(ownerColumn 置 null)为 fail-closed 操作**:服务端在同一平台库事务内强制将该表全部 anon/authenticated ACL 开关一并关闭(owner 策略失效后原 ACL 语义变为全表访问,不允许静默保留),响应中明确告知;调用方如需继续开放访问须再次显式 PUT
- 取消 owner 配置不删除已建索引(无损保留)

## 9. 项目与 Schema 生命周期

### 9.1 项目状态机

```
PROVISIONING → ACTIVE → DELETING → DELETED
      ↓          ↕
    FAILED   MIGRATING(系统表结构迁移中,数据面阻断)→ 完成回 ACTIVE / 失败置 FAILED
    (可重试 provisioning 或清理)
```

创建流程:**先在平台库插入 PROVISIONING 状态的项目记录**(先有状态载体,再产生外部副作用),随后由 Provisioner 账号执行:建 database(显式 `DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci`,即 §13 物理基线;Plan A 已如此实现)→ 建 runtime 账号并仅授权本库 DML → 初始化系统表 → 补全元数据 → 置 ACTIVE。任一步失败置 FAILED 并记录步骤,支持幂等重试;非 ACTIVE 项目数据面一律 403。

**物理前置条件 fail-closed 校验**(声明不够,必须验证):

- 服务启动或 Provisioner 初始化时查询 `@@innodb_page_size`,**不等于 16384 则 readiness 失败**,禁止创建项目与执行一切 Plan B DDL 操作(8KiB/4KiB 页的键长上限分别降为 1536/768 字节,§13 按 3072 校验将放行必然失败的索引)
- 建库后**回读 `SCHEMATA.DEFAULT_CHARACTER_SET_NAME/DEFAULT_COLLATION_NAME`**——`CREATE DATABASE IF NOT EXISTS` 对已存在的库不会修正其默认字符集,回读不符则置 FAILED
- 系统表 DDL **显式携带完整物理基线**(ENGINE/CHARSET/COLLATE/ROW_FORMAT;Plan A 现有语句仅写 ENGINE,Plan B 须补);因 `CREATE TABLE IF NOT EXISTS` 可能命中预存表,初始化后按**版本化系统表 manifest** 全量比对——manifest 覆盖三张系统表的列集合、类型及 signedness、NULL/默认值/EXTRA、主键/自增、索引形状与物理基线,仅查 charset/row format 不够(预存表缺列、错列型、错索引、unsigned 均可能漏过)。比对结果:精确匹配当前版 → 通过;**精确匹配已知历史版本 → 自动迁移**;其他任何偏差 → 项目置 FAILED,**不得置 ACTIVE**(对账跳过 `_` 系统表,错误结构此后无人拦截)。**manifest 版本链(Plan D 起)**:v1 = Plan A unsigned 版、v2 = Plan B signed 版、v3(当前)= v2 + `_users.deleted_at`;已知历史版 v1/v2 各有直达当前版的迁移路径(v1→v3 将 signed MODIFY 与 ADD COLUMN 合并执行,v2→v3 仅 ADD COLUMN)
- **存量项目系统表迁移(unsigned → signed 等)走 ACTIVE → MIGRATING → ACTIVE/FAILED 路径**:**触发入口为服务启动后的后台扫描任务(逐项目 manifest 比对,判定需迁移的项目逐个取锁进入 MIGRATING)+ 管理员手动触发**;MIGRATING 阻断数据面;迁移在项目双层 DDL 锁下**逐表按检查点执行**并**参与项目级 epoch(§9.2,状态转移与每步检查点事务递增并校验)**,每张表 ALTER 前先校验 unsigned 数据未超出 signed 上限(越界则置 FAILED,不产生部分迁移);崩溃后按 `information_schema` 探测已完成的表续跑,不重复执行
- **系统表版本准入(Plan D)**:`baas_project.system_table_version`(§6.1)持久化「已确认的系统表 manifest 版本」——新项目开通置 ACTIVE、存量迁移成功回 ACTIVE、后台扫描比对 MATCH_CURRENT 三处在同一平台库事务写入当前版(v3 即 3),Plan D 迁移回填存量为 0(未确认)。**依赖 v3 新列的端点(全部 `/auth/v1/*` 与 Studio 终端用户列表/软删/恢复)执行任何 SQL 前校验项目 ACTIVE 且 `system_table_version` = 当前版,不满足一律 fail-closed**:数据面 403 + hint「系统表升级未完成」(§11 口径同项目非 ACTIVE),Studio 端点返回明确错误文案——后台扫描默认延迟 10 秒启动、每轮批量有界(§16.2),Plan D 部署后存在「项目 ACTIVE 但仍为 v2 结构」的窗口,不设准入则 login/用户列表/软删会以「未知列 `deleted_at`」500。`/rest/v1/*` 不依赖 v3 新列,不做该准入
- **v3 发布协议(禁止混部,Plan D)**:manifest 比对为**列集合全等**语义(§16.2:`SystemTableManifest.tableMatches` 要求列数完全相等,任何未知列即不匹配),旧(v2 代码)实例的后台扫描遇到已迁 v3 的项目会判 MISMATCH,并按 preflight 失败路径把 ACTIVE 项目置 FAILED——版本准入只能约束新代码,约束不了滚动发布中的旧实例。内部 Alpha 发布协议定为**全量替换(停服发布),含平台库迁移步骤,顺序固定不可颠倒**:① 停止/缩容全部旧 BaaS 实例(Boot 形态停止旧单体);② 执行**幂等的 Plan D 平台库迁移脚本**(`db/ai_work_baas_plan_d_migration.sql`,沿用 Plan B 迁移脚本范式——`information_schema` 探测列存在性、可从任意阶段重复执行;Cloud 对 `ai_work_baas`、Boot 并库形态对 `ai_work` 执行同一语句集):新增 `system_table_version` 列并按 DEFAULT 0 回填存量;③ 校验列名/类型/默认值正确后,才启动 v3 实例——`BaasProject` 为全字段映射实体(§16.2),列缺失时新代码的**一切**项目查询(数据面鉴权、Studio、后台扫描)都以 Unknown column 失败,扫描器根本没有机会补写版本;镜像内初始化 SQL 只作用于**新装**环境,持久化库不会随镜像更新自动加列;④ 执行 manifest 扫描并完成「无项目因 preflight MISMATCH 置 FAILED」的发布验收(§14,含持久化旧库升级验收)。扫描 `initial-delay` 只是缓冲,不作为正确性依据;**禁止 v2/v3 实例混部共存**。未来有无停机要求时须改两阶段发布(阶段一全量部署「认识 v3 结构但不执行迁移」的兼容扫描器并确认全量实例生效,阶段二再启用 v3 迁移),MVP 不实现。**发布验收项**:v3 实例上线后确认无项目因 preflight MISMATCH 被置 FAILED(§14 附混部回归)

### 9.2 DDL 操作

**跨库一致性事实(设计前提)**:项目库 DDL 与平台库元数据分属不同数据源,**不可能同事务**;MySQL 原子 DDL 仅保证单条 DDL 语句自身崩溃一致,DDL 隐式提交、不能与其他语句组成事务。因此一致性靠「单条 ALTER + 检查点 + 探测式重试 + 对账」保障,不声称事务性。

- **串行锁(双层)**:第一层 Redis 分布式锁,锁 key `baas:ddl:{projectId}`,**value = `owner_token`(本次执行唯一:实例标识 + 随机值;不能用 operationId——接管会复用同一 operationId,旧执行者恢复后将无法与新执行者区分)**;`operationId` 只作为业务幂等键。TTL 60 秒 + watchdog 自动续租(每 20 秒),**续租、检查点校验、释放全部为原子 compare 且只比较 `owner_token`**(Lua:value 匹配才延期/删除,禁止 GET+EXPIRE 两步);获取失败返回 409「该项目有 DDL 操作进行中」。**每个检查点推进前校验锁仍为本 `owner_token` 持有**,续租失败或校验不通过立即中止后续步骤。但 Redis 层无法中止**正在执行中**的 DDL(JDBC 阻塞在语句上,取消操作亦不可靠),因此第二层在**执行 DDL 的同一项目库连接**上取 `GET_LOCK('baas_ddl_{projectId}', 0)`,获取失败立即中止——即使 Redis 租约过期、后继操作抢到 Redis 锁,它在数据库边界拿不到 advisory lock,**不会与旧 DDL 并发**。**加锁与所有权顺序固定、DB 锁覆盖全操作**:Redis 锁(新 `owner_token`)→ `GET_LOCK` → 再验 Redis token 仍持有 → **锁内重读日志并分类(SUCCESS → 释放锁返回快照(与快速路径竞态时在此兜住)/活跃 RUNNING(锁被其 owner_token 持有)→ 409/无记录/FAILED/陈旧 RUNNING/PENDING 六类,后四类进入对应分支)** → **按分支重读实际结构并校验该分支允许的状态集**——各分支合法状态相反,不能统一前置:新 ALTER/ACL 只接受 ACTIVE;删表接受 ACTIVE/FAILED/CONFLICT(§9.5,失败残留表必须可删);建表重试接受 CREATING/FAILED;改表重试接受 ALTERING/CONFLICT;cleanup 接受 DELETED 且已到期(统一要求 ACTIVE 会误杀恢复路径,笼统放行非 ACTIVE 又会让新操作命中损坏表或 tombstone);依赖现状的校验(最终索引数量与键长、实际索引名、owner 不变量)同在此步执行,纯 DTO 静态校验(text/json 禁索引、varchar 长度范围等)可在锁外先行,但**凡依赖现状的校验不得沿用锁外快照** → **原子取得日志所有权(按「日志所有权取得」条的对应分支执行)** → 再验日志 `owner_token`/`status` 归属本执行者 → 才允许产生任何副作用(封死「校验后、取 DB 锁前停顿,他人接管完成,本方恢复拿到空闲 DB 锁执行陈旧 DDL」的窗口;所有权在锁内取得,而非先校验后取得);`GET_LOCK` 持有到**平台元数据与日志终态在平台库事务提交之后**才 `RELEASE_LOCK`(MySQL named lock 保持到显式释放或 session 终止,后者为崩溃兜底),不在 DDL 语句结束时提前释放
- **迟到写入隔离(fencing)**:RUNNING 期间 `baas_ddl_log` 的一切状态推进——检查点、SUCCESS/FAILED——均为条件更新 `WHERE owner_token = ? AND status = 'RUNNING'`;丢锁旧执行者恢复后,其续租(token 不匹配)、释放(token 不匹配)与日志写入(条件更新 0 行)全部落空。**平台元数据变更与对应日志检查点必须在同一平台库事务内提交,并以该 owner_token 条件更新作为事务守卫——条件更新影响 0 行则整笔回滚**,封死「他人已接管完成、本方恢复后仍覆盖平台元数据」的窗口(仅日志 CAS 失败、元数据却已写入的结果不允许存在)。**owner_token 只隔离同一条日志行;不同 operationId 之间的迟到覆盖由下一条项目级 epoch 拦截**
- **项目级单调 fencing(`ddl_fence_epoch`)**:owner_token 守卫对「A 丢失双锁 → B 以**不同 operationId** 完成操作 → A 恢复后写自己的日志行(token 仍匹配,守卫恒过)」无效,A 仍可能用陈旧快照覆盖 B 的新元数据(若 A/B 是反向 ACL 更新甚至会重新开放匿名权限)。因此:每次真正进入执行路径时,在所有权短事务中**原子递增 `baas_project.ddl_fence_epoch`** 并把新值写入本操作日志行(`fence_epoch`);**其后一切平台元数据、检查点与终态事务必须 `SELECT ... FOR UPDATE` 锁定项目行并校验「当前项目 epoch = 执行者 epoch」,不匹配整笔回滚**;项目删除、项目物理清理、**系统表 MIGRATING 迁移(§9.1)**等无普通操作日志的参与者同样在其事务中递增并校验同一 epoch。Redis 锁(互斥)、GET_LOCK(DB 边界防并发 DDL)、owner_token(单日志隔离)、epoch(项目级顺序隔离)四层并存,各解决一个层面
- **日志所有权取得(四分支,均在双层锁内原子执行)**。**事务提交边界**:所有权变更(INSERT/CAS)与对应的表状态置位(CREATING/ALTERING)、`step = 'PREPARED'` 写入、**项目 `ddl_fence_epoch` 的原子递增与落表(写入本操作日志行)**构成**一个短平台库事务,必须在任何项目库副作用之前提交**——禁止把所有权与后续元数据更新放进同一个长事务(否则「DDL 已生效、平台事务回滚」会留下无日志或旧所有权的真实 DDL);**事务内统一顺序**:① `SELECT baas_project ... FOR UPDATE`;② 计算并更新 `ddl_fence_epoch = 旧值 + 1`;③ 执行对应分支的 INSERT/CAS,**四分支一律写入 `fence_epoch = :newEpoch`**;④ 分支 CAS/INSERT 失败(0 行/唯一键冲突需改走他支)则**整笔回滚,项目 epoch 增量一并撤销**。该事务**提交成功后,锁内重读确认 `owner_token = :newToken AND status = 'RUNNING' AND fence_epoch = :newEpoch`,才可执行项目库 DDL**;提交失败或结果不确定时不得产生任何项目库副作用,只能锁内重读判定。所有权确立后才进入 RUNNING fencing。**step 语义(只前进不回退)**:仅新操作在所有权事务中初始化 `step = 'PREPARED'`;PENDING cleanup 预建时已是 PREPARED,认领保持不变;**FAILED 重试与陈旧 RUNNING 接管保留原 step**(如已达 DDL_APPLIED 则据此探测续跑,重置会破坏检查点与审计语义)。四分支:
  - **新操作**:`INSERT` 日志行(`status = 'RUNNING'`、`owner_token = :newToken`、`step = 'PREPARED'`、`fence_epoch = :newEpoch`);`(project_id, operation_id)` 唯一键冲突说明记录已存在 → 锁内重读,按其余分支处理
  - **FAILED 重试**:`UPDATE baas_ddl_log SET owner_token = :newToken, status = 'RUNNING', retry_count = retry_count + 1, fence_epoch = :newEpoch WHERE id = :id AND owner_token = :observedToken AND status = 'FAILED'`;0 行(被并发重试者抢先)→ 409。并发 FAILED 重试有且仅有一个执行者成功
  - **陈旧 RUNNING 接管**:见「陈旧 RUNNING 接管」条的 CAS(同样 `SET fence_epoch = :newEpoch`)
  - **待执行 cleanup(PENDING)**:先在锁内按表 ID 重读元数据行完成分支校验(DELETED 且 `deleteAfter` 已到期,见「加锁与所有权顺序」条),通过后执行认领 CAS `UPDATE baas_ddl_log SET owner_token = :newToken, status = 'RUNNING', fence_epoch = :newEpoch WHERE id = :id AND status = 'PENDING' AND owner_token IS NULL`;**未到期不认领、保持 PENDING**,不得置 SUCCESS;多实例并发认领仅一个成功
- **超时分离**:DDL 专用执行超时(默认 5 分钟,可配,§13),与数据面 5 秒查询超时无关;超时后结果不确定,不盲判失败,由重试探测确认
- **检查点**(`baas_ddl_log.step`):`PREPARED`(校验通过、日志已落)→ `DDL_APPLIED`(项目库 DDL 已确认生效)→ `METADATA_APPLIED`(平台元数据已更新,即 SUCCESS)。**删表操作不含项目库 DDL**:PREPARED → METADATA_APPLIED(写 tombstone + `deleteAfter` 即 SUCCESS,快照可重放);到期物理 DROP 是**独立的内部操作**(操作类型 cleanup-drop),其 **operation_id(服务端 UUID)在 API 删除写 tombstone 的同一平台库事务中预生成并落 `baas_ddl_log`(`step = 'PREPARED'`、`status = 'PENDING'`、`owner_token = NULL`,记录不可变表 ID 与内部指纹)**,清理任务只通过 PENDING 认领 CAS(见「日志所有权取得」条)认领这条预建记录、不自行生成。**cleanup-drop 无客户端重试者,滞留状态由清理调度器周期扫描发现并处置**:到期 PENDING → 认领 CAS;FAILED → FAILED 重试 CAS(调度器充当重试者);RUNNING 且 Redis 锁已不属于其 `owner_token`(认领后崩溃)→ 陈旧 RUNNING 接管;SUCCESS → 跳过。物理 DROP 采用 `DROP TABLE IF EXISTS`(FAILED/CONFLICT 表软删时物理表可能从未建成,不存在即 no-op)。
- **HTTP 操作的陈旧 RUNNING 兜底**:建/改/删表与 ACL 的完整请求体不持久化(日志只有脱敏 ddl_text 与指纹),调度器**无法代跑**;若客户端消失不再以同 ID 重试,表将永久滞留 CREATING/ALTERING。因此调度器同轮扫描中,对 **Redis 锁已失效且滞留超过阈值(默认 10 分钟,可配)的 HTTP 类型 RUNNING 记录**:取得双层锁后 CAS 置 FAILED,并按操作类型落表状态——create → 表置 FAILED、alter 及带 DDL 的 acl-config → 表置 CONFLICT、无项目库副作用的操作(删表/纯 ACL 开关,step=PREPARED)→ 不改表状态;同 ID 重试仍可从 FAILED 按检查点探测续跑,已生效的 DDL 不丢失**清理任务取得双层锁后必须按表 ID 重读元数据行,确认该行仍存在、状态为 DELETED 且 `deleteAfter` 已到期,方可执行 DROP**;行不存在(已被其他实例清理)、ID 不符或状态非 DELETED(同名重建的是新表 ID,或已被恢复)一律**不得 DROP**,该 cleanup 记录置 SUCCESS 且 `result_snapshot` 标记 no-op(校验不通过即目标已不存在或不再归本操作管,任务目的已消失,不算失败)——双层锁只能阻止并发,识别不了「A 清理完、用户重建同名表、B 的陈旧任务才拿到锁」的顺序竞态,防误删只能靠表 ID + 锁内重读。失败独立重试,不影响 API 删除操作的已完成状态
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
- DELETING 提交后阻止项目连接池新借用并完成 drain;**GET_LOCK 与 DDL 使用同一条 Provisioner 数据源物理连接**——§9.2 的「同连接」要求不因此打折(锁与 DDL 分属两连接时,锁连接掉线即放进并发执行者),而 DDL 本就由 Provisioner 账号执行(§10.1,Runtime 仅 DML),该连接天然不来自项目 Runtime 池、不受 drain 波及;GET_LOCK 为 MySQL 实例级命名锁,不要求连接到项目库
- 项目物理清理(DROP DATABASE)同样取双层锁,锁内复核项目仍为 DELETING 且已到期,并递增校验同一 `ddl_fence_epoch`,方可执行
- 表级 cleanup 遇到非 ACTIVE 项目时不得执行,与项目清理靠同一把项目锁互斥,不会并发操作项目库

### 9.4 对账

手动触发(`/reconcile`,body 携带客户端 `operationId`)+ 可选定时(`@Scheduled` + 配置开关,**默认关闭**,MVP 以手动为主)。对账日志记录**触发来源(MANUAL/SCHEDULED)**。**定时对账的陈旧任务发现(与 cleanup 调度器同型,扫描与决策必须在锁内重做)**:锁外扫描只能作为快速路径(决定是否值得取锁),不得作为执行依据;**取得双层锁后必须重新扫描本项目的 SCHEDULED reconcile 记录**——FAILED → 对原记录执行 FAILED 重试 CAS;RUNNING 且 Redis 锁已不属其 owner_token → 对原记录陈旧接管;RUNNING 且锁仍被持有 → 本轮直接跳过;**锁内确认不存在 RUNNING/FAILED 后才允许以服务端生成的新 operationId 创建新操作**(内部指纹见 §9.2)。否则多实例下「双方锁外读空 → A 插入 RUNNING 后崩溃 → B 沿用锁外决策再插一条」会违反下述约束。**同一项目同一时刻最多存在一个未终结(RUNNING 或 FAILED)的 SCHEDULED reconcile**——若每轮都直接换新 ID,遗留的 FAILED/陈旧 RUNNING 将永远无人认领。**范围仅表结构**:以项目库 `information_schema` 为准修正 `baas_table`/`baas_column`;ACL、owner 配置、密钥属于操作意图,以平台库为唯一事实源,不参与对账。**对账跳过非 ACTIVE 项目及软删/tombstone 表——物理表仍存在不构成「复活」软删除对象的依据**。**对账全程纳入 §9.2 统一日志与所有权模型**:操作类型 `reconcile`(项目级,目标表名与表 ID 为 NULL),走统一入口顺序与双层锁,按四分支取得 RUNNING 所有权;**对账产生的全部平台元数据修正、报告快照(`result_snapshot`)与 METADATA_APPLIED/SUCCESS 在同一平台库事务中提交,采用双重守卫**:① `SELECT ... FOR UPDATE` 锁定 `baas_project` 行并校验 `ddl_fence_epoch = 执行者 fence_epoch`;② 日志更新同时校验 `owner_token`/`status`/`fence_epoch`;任一失败整笔回滚——读完 `information_schema` 后丢双锁、他人以**不同 operationId** 完成并发 ALTER 的场景下,陈旧对账因**项目 epoch 不匹配**整笔回滚(此场景旧日志行的 owner_token 仍匹配,单靠它拦不住,见 §9.2 项目级 fencing)。对账报告即 `result_snapshot`(修正/导入/冲突清单),幂等重放返回原报告。

比对范围覆盖 Plan B 管理的**全部结构要素**:列集合、类型/长度/精度、可空、默认值、注释、主键/自增、唯一索引与普通索引。

**ACTIVE 准入谓词**(导入、按库修正、CONFLICT 恢复 ACTIVE 三条路径前统一校验):`TABLE_TYPE = 'BASE TABLE'`(VIEW 不进 MVP,§15)、`ENGINE = 'InnoDB'`(MyISAM/MEMORY 等非事务引擎破坏 §7.1 批量插入单事务原子契约)、无表级触发器(产生元数据模型不可见的 DML 副作用)、表名与全部列名通过 §12.2 标识符正则与保留字检查(导入不得绕过管理 API 的标识符安全边界)、**物理基线一致**(库 `SCHEMATA` 与表 `TABLES.TABLE_COLLATION` 均为 utf8mb4_general_ci、`ROW_FORMAT='Dynamic'`,且每个 varchar/text 列的 `CHARACTER_SET_NAME`/`COLLATION_NAME` 均为 utf8mb4/utf8mb4_general_ci——排序规则改变比较语义,元数据相同的表在不同 collation 下数据面行为不同,不允许无损导入)、主键不变量(§13:唯一主键为 `id` bigint 自增)、无生成列/外键/**CHECK 约束**(查 `TABLE_CONSTRAINTS`/`CHECK_CONSTRAINTS`,**存在任何 CHECK——包括 NOT ENFORCED——均视为不可映射**)等不支持结构、类型与索引结构可映射(**类型比较基于 §13 逻辑—物理规范化后的逻辑模型与类型参数矩阵**,如 tinyint(1) ↔ boolean;**UNSIGNED/ZEROFILL 一律拒绝映射**)。**列 EXTRA 允许集合**(对账、导入与探测式续跑共用同一规范化逻辑):`id` 列仅允许 `auto_increment`;datetime 列的 `CURRENT_TIMESTAMP` 默认值允许并将 `DEFAULT_GENERATED` 规范化处理(不产生漂移);普通列仅允许空 EXTRA;**`on update CURRENT_TIMESTAMP` 及其他未建模属性一律不可映射**(平台模型只记录默认值,表达不了自动更新行为,导入后 MODIFY 还会静默删掉它)→ 按既有规则 CONFLICT/REJECTED_IMPORT。**「单列索引可映射」的精确谓词**:该索引恰好一个 key part、`COLUMN_NAME` 非空且 `EXPRESSION IS NULL`(排除函数/表达式索引)、`SUB_PART IS NULL`(排除前缀索引)、`INDEX_TYPE = 'BTREE'`(排除 FULLTEXT 等)、`IS_VISIBLE = 'YES'`(排除不可见索引)、`COLLATION = 'A'`(排除降序);`NON_UNIQUE` 映射为 `unique` 布尔位;不满足谓词的索引一律视为不可映射结构。**owner 索引不变量(§7.3/§8.3/本节)只把满足该谓词的索引计为有效**。**违反时的处置按元数据是否存在拆分**:已有元数据的表 → `baas_table.status = CONFLICT`(状态有载体);**无元数据的外部表 → 不创建元数据行,在对账报告中记为 `REJECTED_IMPORT` 及原因**(CONFLICT 是 `baas_table.status`,没有行就没有状态可置;数据面因无元数据继续 404),结构修复后下次对账按正常路径导入并置 ACTIVE。

逐表处理规则:

对账**只处理 ACTIVE 与 CONFLICT 状态的表**(CONFLICT 参与以判定恢复);CREATING/ALTERING/FAILED/DELETED 属生命周期中间态或 tombstone,由各自的操作恢复路径(§9.2 重试/接管/兜底、cleanup)负责,对账一律跳过——否则「元数据有、库无表」规则会把物理表尚未建成的 FAILED/CREATING 表误判为 CONFLICT,抢走恢复路径。

| 情形 | 处理 |
|---|---|
| 元数据有、项目库无该表 | 表置 CONFLICT |
| 项目库有、元数据无(非 `_` 前缀) | **先过 ACTIVE 准入谓词**:通过 → 导入元数据,状态 ACTIVE,**ACL 默认全关**(安全兜底);不过 → **REJECTED_IMPORT 报告留痕,不创建元数据行** |
| **主键不变量破坏**:缺少 `id` 列、`id` 非 bigint/非自增/非唯一主键、复合主键或存在额外主键(§13 固定主键约束) | 准入谓词失败:有元数据 → 表置 CONFLICT;无元数据 → REJECTED_IMPORT |
| **不支持结构**:生成列(`information_schema.columns` 的 EXTRA/GENERATION_EXPRESSION 探测)、外键(约束表探测)、**CHECK 约束(含 NOT ENFORCED)**、VIEW、非 InnoDB 引擎、表级触发器、非法标识符、**不满足单列索引谓词的索引(前缀/FULLTEXT/不可见/降序/函数索引)** | 准入谓词失败:有元数据 → 表置 CONFLICT,不做普通修正(元数据模型无法表达,压缩会静默丢失结构信息);无元数据 → REJECTED_IMPORT |
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
FAILED   → ACTIVE                          建表同 ID 重试探测续跑成功
ACTIVE   ⇄ ALTERING                        改表开始/成功返回(执行中,短暂)
ALTERING → CONFLICT                        改表中断或失败(保持阻断)
ACTIVE   → CONFLICT                        对账发现结构不一致
CONFLICT → ACTIVE                          探测式重试续跑成功,或对账确认结构一致
ACTIVE / FAILED / CONFLICT → DELETED(tombstone,+N 天,默认 7) → 到期物理 DROP 并删除元数据行(§9.3)
```

**FAILED/CONFLICT 表允许删除**(走同一软删 tombstone + cleanup 路径,cleanup 按表 ID DROP 时物理表不存在即 no-op)——否则建表失败的残留元数据行因 `unique(project_id, table_name)` **永久占用表名**,无路径释放。

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
- 映射:唯一键冲突→409,表不存在→404,列名/语法非法→400,ACL 或 owner 策略拒绝→403,apikey 无效/JWT 无效→401,项目非 ACTIVE→403,超资源限制→413/429,SQL 执行超时→500(脱敏 message;资源保护动作,非客户端可修复错误)
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
- CORS:数据面支持 per-project 允许来源配置(`baas_project.allowed_origins`),MVP 默认 `*` 可收紧,**默认 `*` 时固定 `allowCredentials=false`**。OPTIONS 预检不携带 apikey,由 CORS Filter 在 ApiKeyAuthFilter **之前**处理:仅按 URL projectRef 查平台元数据,不创建任何项目库连接;projectRef 不存在时预检直接放行且不返回任何 CORS 头(不泄露项目存在性)。**完整响应契约(Plan C 落地,仅 Origin 不足以支持浏览器调用)**:允许方法 `GET/POST/PUT/PATCH/DELETE/OPTIONS`(**PUT 为 Plan D 改密端点所需**:Plan C 已合入的 `DataPlaneCorsFilter.ALLOW_METHODS` 未含 PUT(§16.2),Plan D 须同步补上,否则浏览器对 `PUT /auth/v1/user/password` 预检失败);允许请求头 `apikey, Authorization, Content-Type, Prefer`;`Access-Control-Expose-Headers: Content-Range`(否则浏览器读不到 `count=exact` 结果);预检 `Access-Control-Max-Age` 可配(默认 3600);实际响应与预检均设置 `Vary: Origin`(预检另加 `Vary: Access-Control-Request-Method, Access-Control-Request-Headers`),防止 per-project Origin 回显被中间缓存跨项目复用
- Auth 防暴力(Plan D 细化):Redis **固定窗口计数**,计数以**单个 Lua 脚本原子完成「INCR + 计数从 0→1 时 EXPIRE + 返回计数与剩余 TTL」**——禁止 INCR 与 EXPIRE 两条命令分步执行(进程在两命令之间失败会留下**无 TTL 的计数键**,该维度被永久封禁);EXPIRE 仅在键新建时设置一次(每次重设过期时间会退化为滑动窗口,窗口语义漂移)。**Redis 不可用时限速 fail-open**:放行请求并记结构化 error 日志(内部 Alpha 取可用性优先,bcrypt cost 10 仍保底暴力成本);**该 error 日志须限频**(如每项目每分钟至多一条)或改以指标/告警暴露,避免 Redis 故障期间每请求一条形成日志风暴。平台 `RedisUtils.get` 采用 JDK 反序列化、读不了 INCR 写入的值,须直接用 StringRedisTemplate 执行上述脚本(见 §16.2)。三组默认阈值(均可配):login 失败与改密 currentPassword 错误计入 **(项目, 规范化邮箱)5 次 / 15 分钟** 与 **(项目, 客户端 IP)30 次 / 15 分钟**;signup 计入 **(项目, 客户端 IP)10 次 / 1 小时**(无论成败)。超限 → 429 + `Retry-After`(窗口剩余秒);登录成功清除该邮箱维度计数;refresh/logout 不限速(token 高熵,穷举不可行)。**客户端 IP 判定(不得无条件信任 `X-Forwarded-For`)**:仅当 `remoteAddr` 属于可配置的**可信代理列表**(`baas.auth.trusted-proxies`,CIDR 列表,默认空)时才读取 XFF;Cloud 形态由网关**覆盖**入站 XFF(丢弃客户端自带值、写入网关观察到的对端 IP)后转发,服务端读到的即真实客户端 IP,**trusted-proxies 的注入方式与部署边界(网关固定 IP /32、baas 端口不对外、禁止绕网关直连)为 §5 交付物**;Boot 形态无网关(§16.2),列表保持默认空、恒用 remoteAddr——无条件取 XFF 最左条目时,攻击者逐请求轮换伪造头即可绕过 IP 维度限速。Redis 键中的邮箱以 SHA-256 摘要存储,不落原文
- 日志脱敏:password、key、JWT 不落日志;DDL 与密钥操作入 `baas_audit_log`
- 限流:MVP(内部 Alpha)不新增网关限流配置,以服务内资源限制(第 13 节)兜底慢查询;gateway/sentinel QPS 阈值留对外形态时配置

## 13. 资源限制(MVP 默认值,可配)

| 项 | 限制 |
|---|---|
| 请求体大小 | 1 MB(数据面在鉴权前按 Content-Length 预检,超限 413;body 读取时流式兜底) |
| 响应体大小 | 16 MiB(可配;行数上限不约束字节量——1000 行大 text/json 仍可达数百 MB。序列化时逐行计数,超限在响应提交前返回 413;POST/PATCH representation 超限**回滚事务**,写入不落库) |
| 并发响应构建 | 信号量默认 8(可配;序列化阶段前取许可、finally 必然释放,无许可 429——响应缓冲堆内存理论上限 = 许可数 × 响应体上限,与连接预算解耦) |
| 批量插入行数 | 1000 行 |
| 过滤条件数量 | 每请求 20 个 |
| SQL 执行超时 | 5 秒(JDBC queryTimeout,仅数据面) |
| DDL 执行超时 | 5 分钟(独立于数据面超时,超时后按 §9.2 探测式重试确认结果) |
| 单项目连接池 | max 10;全服务总连接预算另设上限 |

### 表编辑器能力边界

- 类型白名单:`bigint / int / decimal(p,s) / varchar(n≤4096) / text / boolean / date / datetime / json`
- **类型参数矩阵**(管理 API 校验、DDL 渲染、对账与探测续跑共用同一实现):`int`/`bigint` 的 length/scale 必须为空,**一律渲染为 signed**(与 `_users.id` 对齐,Java Long 全值域承载);`decimal` 要求 `1 ≤ p ≤ 65`、`0 ≤ s ≤ min(30, p)`;`varchar` 要求 `1 ≤ length ≤ 4096` 且 scale 为空;`text/boolean/date/datetime/json` 的 length/scale 均必须为空;数值默认值解析时**必须落在目标列值域内**(如 int 默认值超 2^31−1 → 400)。**UNSIGNED/ZEROFILL 不建模**:外部结构带 UNSIGNED/ZEROFILL(含整数与 decimal)一律视为不可映射,按 §9.4 置 CONFLICT/REJECTED_IMPORT。**时间精度与显示宽度映射规则**:`date`/`datetime` 仅 `DATETIME_PRECISION = 0` 可映射,`datetime(n>0)` 等带小数秒精度的外部列拒绝映射(模型无精度参数);`int`/`bigint` 的**显示宽度忽略**——`COLUMN_TYPE` 为 `int` 与 `int(11)` 等价映射(MySQL 8.0.19+ 已弃用显示宽度,旧表回读仍可能带宽度,不得按字面比对误拒)
- **boolean 的逻辑—物理规范化层**(MySQL 将 BOOLEAN 作为 TINYINT(1) 同义词,`information_schema` 回读为 tinyint):① DDL 固定渲染 `boolean → TINYINT(1)`;② 对账/准入把 `DATA_TYPE = 'tinyint'` 且 `COLUMN_TYPE = 'tinyint(1)'` 映射回逻辑 boolean,**其他 tinyint 变体(如 tinyint(4)、unsigned/zerofill)拒绝映射**(视同白名单外);③ `COLUMN_DEFAULT` 的 0/1 分别规范化为 JSON false/true;④ 类型兼容判定、导入、按库修正与探测式续跑**全部基于规范化后的逻辑模型比较**,避免平台自建 boolean 列被误判出白名单或每轮对账产生默认值漂移
- 默认值:类型化常量或 `CURRENT_TIMESTAMP`(仅 datetime 列),按 §7.3 类型化模型解析渲染;`text`/`json` 列不支持默认值
- 约束:主键固定为自增 `id bigint`(建表自动生成);单列唯一、单列索引;建表语句**显式 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC`**(数据面事务契约依赖 InnoDB;排序规则直接改变 eq/like/unique 的大小写与尾随空格语义,必须全平台唯一基线;§9.4 ACTIVE 准入谓词同此要求)。列不提供 charset/collation 字段,不允许单列覆盖基线
- **索引准入矩阵**(create/add/modify/索引开关统一校验,任一不过 400、不记日志、不执行 DDL;**纯 DTO 项可锁外预检,依赖现状的项——最终索引数量、键长合计、实际索引名——必须按 §9.2 固定顺序在双层锁内重读后校验**):① `text`/`json` 列 `unique=true` 或 `indexed=true` 一律 400(text 的 BTREE 索引必须指定前缀而前缀索引已禁止;json 需生成列或函数索引而两者均禁止);② varchar 索引键长按 utf8mb4 计算 **`length × 4 ≤ 3072` 字节**(前置条件 `innodb_page_size=16KiB` + ROW_FORMAT=DYNAMIC,即全列索引 length ≤ 768;**modify 扩长已带索引的 varchar 越界同样 400**);③ 最终**总 key 数 ≤ 64**(InnoDB 上限),托管表固定 PRIMARY KEY 后最多 63 个二级索引
- 改表能力(全能档):加列、删列、改类型/长度、改可空/默认值/注释、单列唯一/索引增删、重命名列/表;契约见 §7.3,破坏性操作需 `allowLossy` 确认
- **不支持**:复合主键/复合索引/复合唯一、外键、生成列、**CHECK 约束**(MySQL 8.0.16+ 会实际执行已启用的 CHECK,影响数据面写入与后续改列/重命名/删列,而 `baas_table`/`baas_column` 模型无法表达其条件;`baas_column` 模型即按此约束设计)

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
- **Plan B(表管理与 DDL)必测**:类型兼容矩阵判定单测(无损/有损分类全覆盖);有损转换不带 `allowLossy` → 400、带且数据不兼容 → 409 不截断、可空→非空含 NULL 行 → 409;rename owner 列联动更新 `owner_column`、`id` 列保护;**drop owner 列 fail-closed**(ACL 同步全关、无越权窗口)与**取消 owner 配置强制关 ACL**;tombstone 同名禁重建(建表与表重命名两条路径)、物理清理后名称可复用;对账各情形(§9.4)各一例,**含 owner 约束破坏(列缺失/非 bigint/索引丢失)置 CONFLICT 不修正**;DDL 锁互斥、锁续租(原子 CAS)与锁丢失中止、**watchdog 停顿超过 TTL 时 DB advisory lock 兜底**(第二操作拿到 Redis 锁但在 GET_LOCK 处被拒,不与旧 DDL 并发);幂等重放(指纹一致返回快照、**指纹不一致 409**,含 DELETE 同 ID 删不同表被指纹区分)、`DDL_APPLIED` 断点探测式续跑、**陈旧 RUNNING 接管**(模拟进程崩溃后 CAS 接管续跑)、**接管后旧执行者隔离**(A 超时、B 接管后:A 续租失败、无法释放 B 的锁、日志条件更新 0 行,不覆盖 B 的检查点;**含 A 在取 GET_LOCK 前停顿、A 在写平台元数据前停顿两个窗口**——恢复后均不产生陈旧 DDL 或元数据覆盖,元数据事务因守卫 0 行整笔回滚);**并发 FAILED 重试仅一个执行者 CAS 成功**、连续多次失败 retry_count 递增;**陈旧 cleanup 不误删同名新表**(旧表清理完成、同名重建后,预建 cleanup 记录按表 ID 校验不通过、不执行 DROP)、**未到期 PENDING 不被认领**(保持 PENDING、不置 SUCCESS)、**多实例并发认领同一 PENDING 仅一个 CAS 成功**、**PENDING→RUNNING 后立即崩溃可被调度器自动接管**、**DROP 已执行而终态提交前崩溃时探测式续跑**、**FAILED cleanup 由调度器自动重试**;**所有权事务提交失败不产生任何项目库副作用**、**所有权事务提交成功后崩溃可被接管续跑**、**DDL_APPLIED 状态下重试/接管不重置检查点**;**陈旧对账不覆盖新元数据**(对账读完结构后丢锁、并发 ALTER 完成,旧对账整笔回滚);**跨 operationId 的项目级 epoch fencing**(A 在最后一次锁校验后停顿并丢失双锁,B 以不同 operationId 完成操作,A 恢复后整笔事务因项目 epoch 不匹配回滚;至少覆盖 reconcile-vs-ALTER 与 ACL 关闭-vs-开启两组);**SCHEDULED reconcile 的 RUNNING 崩溃由下一调度周期接管**、**FAILED 自动重试**、**多实例调度不产生重复任务**(两个调度器锁外同时读空,第一个写入 RUNNING 后崩溃,第二个锁内重查后接管原记录、不创建新记录);**迁移后 Plan A 建项目路径不改造仍成功且 `ddl_fence_epoch` 初始为 0**;**四分支所有权取得后日志 fence_epoch 与项目 epoch 相等**(参数化覆盖新操作/FAILED 重试/RUNNING 接管/PENDING 认领);**管理 API 不得删除 owner 列最后一个索引**(modifyColumns unique=false,indexed=false → 400;unique→普通索引同一 ALTER 替换);**非规范索引名可被正确定位**(外部建 `foo_email` 索引 → reconcile 导入 → PATCH 关闭索引/重命名列,DDL 按 information_schema 实际名生成且执行成功);**ACL PUT ownerColumn=id → 400 且不改 ACL/索引**;**对账准入拦截**——已有元数据的表违反约束置 CONFLICT、无元数据的外部表拒绝导入且报告留痕 REJECTED_IMPORT(两类断言分开,不得同时断言「不导入」与 `status=CONFLICT`),用例覆盖:无 `id`、`id` 类型/自增/主键性质错误、复合主键、生成列、外键、**列级与表级 CHECK 约束(含 NOT ENFORCED)**、**VIEW、MyISAM、表级触发器、非法表名/列名**、**不满足单列索引谓词的索引(前缀唯一、不可见、FULLTEXT、DESC、函数索引各一例)**;**建表语句显式 ENGINE=InnoDB**;**boolean 规范化闭环**(创建含 true/false 默认值的 boolean 列 → 查询 information_schema 回读 tinyint(1) 与 0/1 → 对账无差异不产生漂移;tinyint(4)/unsigned 变体拒绝映射);**物理基线三例**(平台建表回读 charset/collation/row_format 符合基线;外部表表级排序规则不同 → 拦截;外部表单列覆盖排序规则 → 拦截);**索引准入矩阵四例**(text/json 索引请求 400、varchar 键长边界 length=768 通过且 769 拒绝、已带索引的 varchar 扩长越界 400、第 65 个二级索引 400,均断言不执行 DDL);**依赖现状校验锁内重做**(两个并发请求均按"当前 63 个索引"锁外预检通过,后取锁者锁内重查后 400,且不记日志、不执行 DDL);**物理前置 fail-closed 三例**(page size 非 16384 → readiness 失败禁止建项目、预存错误字符集 database → 建库回读置 FAILED、预存错误基线系统表 → 回读不过不得 ACTIVE);**类型参数矩阵**(decimal(66,31)/varchar(0)/date 带 length → 400,数值默认值越界 400,外部 unsigned/zerofill → CONFLICT/REJECTED_IMPORT,**owner 列与 `_users.id` 精确类型一致——含 signedness**);**四类恢复路径不被前置状态校验误杀**(建表重试于 CREATING/FAILED、改表重试于 ALTERING/CONFLICT、cleanup 于 DELETED 到期、新操作仅 ACTIVE——按分支分类后各自放行/拦截);**系统表 manifest 与迁移闭环**(预存表错列/错索引 → FAILED 不得 ACTIVE、匹配 Plan A unsigned 版自动走 MIGRATING 迁移、迁移中数据面被阻断、迁移中崩溃按 information_schema 续跑、unsigned 越界值不产生部分迁移);**EXTRA 允许集合**(外部表 on update CURRENT_TIMESTAMP → CONFLICT/REJECTED_IMPORT;平台 datetime CURRENT_TIMESTAMP 默认值的 DEFAULT_GENERATED 对账无漂移);**索引名占用探测**(其他列的外部索引占用 `idx_email`/`uk_email` 后,email 列新增索引、唯一/普通替换及列重命名仍成功,分配器落到备用名);**长 ALTER 与项目删除互斥**、**表级 cleanup 与项目 DROP DATABASE 不并发**(同一项目锁);**SUCCESS 重放不取锁**(他人长时间持有 DDL 锁时,已成功操作的同 ID 重放仍立即返回原快照而非 409);**ACL PUT 与改表串行化**(并发 ALTER 删/改 owner 列时 ACL 配置不产出损坏的 owner 约束)、**ACL 幂等重放不覆盖新配置**(请求成功 → 另一请求修改 → 原 operationId 重放仅返回旧快照);**删表 tombstone 快照重放**与 cleanup-drop 独立取锁重试;**超过 255 字符的注释/默认值元数据双写成功**(验证迁移扩容);**ACL 索引被外部删除后再次设置 owner 可重建**;**64 字符列名生成合法索引名**(截断 + 哈希后缀 ≤ 64);**DDL 注入尝试**(默认值/注释携带引号、注释符、子查询等恶意载荷,断言渲染后 DDL 无原样拼接);**FAILED/CONFLICT 表可删除并释放表名**(软删 → cleanup no-op(物理表不存在)→ 元数据行物理删除后同名可重建);**HTTP 陈旧 RUNNING 兜底**(客户端消失、锁失效超阈值后调度器置 FAILED 且表状态按类型落位(create→FAILED、alter→CONFLICT),同 ID 重试仍从检查点续跑);**锁内分类发现 SUCCESS 返回快照**(两个同 ID 请求竞速,后进锁者锁内发现已 SUCCESS,返回原快照不重复执行);**acl-config 补索引期间表置 ALTERING 阻断数据面、纯开关分支不改表状态**;**对账只处理 ACTIVE/CONFLICT 表**(FAILED/CREATING 表不被「库无表」规则误判为 CONFLICT);**datetime(6) 外部列拒绝映射、int(11) 显示宽度等价映射不误拒**;**项目池 drain 期间 DDL 锁连接不受影响**(GET_LOCK 走 Provisioner 连接);**MIGRATING 由启动扫描触发且参与 epoch**(Testcontainers 真 MySQL)
- **Plan C(数据面 REST)必测**:解析器与 SqlBuilder 单测覆盖操作符全矩阵(eq/neq/gt/gte/lt/lte/like/in/is × 各逻辑类型)、非法列/非法操作符/类型解析失败 400、`in.()` 与值内嵌逗号 400、json 列非 is 操作符 400、order 多列与非法列、limit/offset 边界与 20 条过滤上限;**SQL 注入载荷**(列名/值/order 携带引号、注释符、子查询)断言全部参数化绑定或 400、DDL 原文无拼接;URL/apikey/JWT 三方一致性矩阵、secret key 携带 JWT 401、kid 三态(CURRENT/PREVIOUS 未过 `valid_until`/REVOKED)与过期边界、**紧急轮换后立即 401(密钥无缓存直查)**;ACL × 角色 × 四操作全矩阵;owner 策略按 §8.3 表逐格覆盖(含 body 携带 owner 列 400、anon `owner IS NULL` 读写、service_role 绕过);表状态阻断矩阵(ACTIVE 放行、DELETED/无表 404、CREATING/ALTERING/FAILED/CONFLICT 403)与项目非 ACTIVE 403;POST/PATCH body 含 `id` 400;批量插入部分失败整体回滚、1000 行/1MB/limit 1000 边界(413/400);`return=representation` 与 `count=exact` 的 Content-Range 语义;CORS 预检不触碰项目库连接(断言 ProjectDataSourceRegistry 零调用)、默认 `*` 时 allowCredentials=false、projectRef 不存在无 CORS 头;**bigint 输出 JSON number、datetime 输出 `yyyy-MM-dd HH:mm:ss`、XSS 清洗不改写数据面 body**(写入含 HTML 的字符串读回原样);queryTimeout 5 秒生效且超时 500 脱敏;`last_used_time` 节流更新;boot 冒烟测试补 `/data/{ref}/rest/v1/{table}` 路由断言;Testcontainers 全链路(建项目 → 建表 → 开 ACL → anon/authenticated/service_role 三态 CRUD);`db/ai_work.sql` 与 `db/ai_work_baas.sql` 的 baas 表 DDL 逐语句一致性断言;**平台过滤链隔离**(携带项目 JWT 的 `/data/**` 请求断言平台 introspector 零调用、ApiKeyAuthFilter 被调用,`skip-resolve-urls` 在 Cloud/Boot 双形态均生效,且不配置该项的存量服务行为不变);**JWT 负面矩阵**(过期 exp、未来 iat(±60 秒偏差边界)、缺失 exp/session_id、`alg=none`/`HS384`、`exp−iat` 超 1 小时,均 401);**TOCTOU 并发两例**(请求读完旧元数据后并发 ALTER 完成 → 屏障内重读阻断或按新结构执行,不以旧 AST 落库;ALTER 完成后陈旧请求恢复同断言);**PATCH representation 三例**(修改过滤列自身仍返回全部被改行、并发插入的新匹配行不进 representation、捕获超 1000 行 400);**线协议往返矩阵逐格覆盖**(含 decimal 字符串 token、boolean 拒 0/1、json 列真实 JSON 输出、显式 null 与缺失字段各自语义、批量键集合不一致 400);**CORS 契约**(PATCH 预检方法与请求头放行、浏览器可读 Content-Range(Expose-Headers)、Vary 断言);**compose 落地**(两份 `docker compose config` 通过、BaaS 镜像构建成功、cloud baas 服务缺 `BAAS_MASTER_KEYS` 时 fail-fast);**representation 的 select 权限前置**(insert=true+select=false 的 POST 与 update=true+select=false 的 PATCH 各带 representation → 403,**并断言未插入/未更新任何行**;不带 representation 时同权限组合正常放行;service_role 不受限);**响应体上限**(GET、POST representation、PATCH representation 三条路径超 16 MiB → 413,且 representation 超限断言写入未落库、行数不超 1000 但字节超限同样拦截);**真流式配置断言**(数据面 PreparedStatement 断言 TYPE_FORWARD_ONLY/CONCUR_READ_ONLY/fetchSize=100、注册表 JDBC URL 含 `useCursorFetch=true`——只验证最终 413 无法证明驱动未提前物化,必须直接断言配置生效);**并发响应构建信号量**(许可耗尽时第 9 个请求 429、不阻塞;成功/异常/413 三种出口后许可数恢复满额)
- **Plan D(终端用户 Auth)必测**:Testcontainers 全链路(signup 即登录 → 凭签发 JWT 走 owner 策略 CRUD → refresh → logout → 改密撤销全部会话);**refresh 四分支**(正常轮换、grace 内重放返回同一响应、超窗复用撤销整个会话、过期/不存在/会话 REVOKED 均 401)与并发 refresh(grace 内/外,§14 既有安全场景);**鉴权规则**(`/auth/v1/*` 缺 apikey 401、secret key 调用 403、logout/user/改密缺 JWT 401);**请求体上限**(auth 端点复用数据面有界读取:无 Content-Length/chunked 且超 1 MiB → 413 流式兜底,且服务层未触达、项目库无写入);**响应形态**(signup/login/refresh 同构、login 三类失败统一 401 文案、signup 邮箱占用——含软删用户——409、logout 幂等 204);**改密**(currentPassword 错误 401 且计入限速、成功后全部会话撤销含当前、bcrypt 72 字节边界 400——**currentPassword 与 newPassword 同受 8–72 字节约束,73 字节 currentPassword → 400**、邮箱 trim+小写规范化);**软删闭环**(软删后 login 拒绝、会话即时撤销、存量 access JWT 在 TTL 内仍可访问 `/rest`、**但 `GET /user` 与 `PUT /user/password` 对软删用户返回 401、logout 仍幂等 204**、restore 后可重新登录且旧会话不复活、重复 DELETE 与未软删 restore 幂等、软删/恢复入审计日志);**manifest v3 迁移**(v1/v2 存量项目各自动迁移至 v3、迁移中 MIGRATING 阻断数据面、迁移中崩溃按 information_schema 续跑、新项目直建 v3 通过 MATCH_CURRENT);**防暴力限速**(邮箱与 IP 两维度分别触发 429 + Retry-After——**login IP 30 次维度以不同邮箱独立触发**、登录成功清邮箱计数、Redis 键无邮箱原文、refresh 不计数;**并发失败登录不得穿透阈值**——基于原子 INCR 返回值硬闸,至多 limit 个以 401 通过、其余 429,消除 check-then-act 竞态);**JWT 轮换**(常规轮换存在未过期 previous → 409、previous 在 valid_until 内验签通过、紧急轮换后原 current/previous 签发的 JWT 均立即 401 且 refresh token 仍可换新——§14 既有场景、并发轮换不产生双 CURRENT);**签发器直查**(签发后立即紧急轮换,旧 kid 拒绝——断言签发与验签均无缓存);**grace 清理与复用检测保全**(定时任务清除超窗密文与**已过期** token 行、不误删 grace 窗口内密文;**已消费未过期行仅清密文不删行——清理任务运行后,超窗重放旧 token 仍判定泄露、会话置 REVOKED,而非落入「不存在→401」**);**系统表版本准入**(存量 v2 项目 ACTIVE 且扫描未及时:login/用户列表/软删/恢复 fail-closed 返回明确错误而非缺列 500;迁移完成写入 v3 后放行;**auth 端点与 Studio 用户列表/软删/恢复在 MIGRATING、FAILED、DELETING 三种状态下逐一断言阻断**——现 `ProjectAccessService` 仅排除 DELETED,直接复用会放行这三态,见 §16.2);**version 三条写入路径**(新项目开通:置 ACTIVE 与写入当前版在同一平台库事务、断言原子可见;物理结构已是 v3 而 `system_table_version = 0` 时后台扫描按 MATCH_CURRENT 补写;迁移失败置 FAILED 时绝不写入当前版);**混部回归与发布验收**(以 v2 manifest 比对逻辑对 v3 结构断言判 MISMATCH——固化混部危害证据,支撑 §9.1 停服发布协议;发布验收:v3 实例上线后无项目因 preflight MISMATCH 置 FAILED);**持久化旧平台库升级**(以 Plan C 版平台库 schema + 存量项目数据起库 → 执行 `db/ai_work_baas_plan_d_migration.sql` → 断言 `system_table_version` 列名/类型/DEFAULT 0 回填正确、**脚本二次执行幂等无害**、随后 v3 代码的项目查询与后台扫描正常且能补写版本;**不得只覆盖全新初始化路径**——镜像内初始化 SQL 对持久化库不生效;Cloud `ai_work_baas` 与 Boot 并库 `ai_work` 两种目标库均覆盖);**session_id 严格解析与单会话 logout**(session_id 为字符串/非数值/越界 → 401;logout 仅撤销 JWT 所指会话,同用户其他会话与 refresh token 不受影响);**CORS PUT 预检**(`PUT /auth/v1/user/password` 的 OPTIONS 预检放行);**限速原子性与边界**(计数键任意时刻均有 TTL——含首次 INCR 后立即断言;窗口内多次请求不重设过期;阈值第 N 次放行、第 N+1 次 429 + Retry-After;Redis 不可用时 fail-open 放行并留 error 日志);**XFF 信任边界**(BaaS 侧:remoteAddr 不在可信代理列表时伪造 X-Forwarded-For 不影响 IP 维度计数,列表命中时按 XFF 计数;网关侧:自定义 GlobalFilter 剥离入站 X-Forwarded-For/Forwarded 并覆盖为真实对端 IP——断言下游收到真实对端 IP 而非伪造值);**审计 best-effort**(注入平台库审计写入失败:软删/恢复仍成功返回、项目库变更已提交、error 日志留痕)
- **Plan E(Studio 前端)必测**(Vitest 单测,前端门禁):**改表操作列表组装**(重命名与删+加的区分、同列多操作互斥在前端拦截、无操作禁提交、组装出的 PATCH body 与 §7.3 契约字段逐项一致);**列定义校验边界**(varchar 长度 1/4096/越界、decimal 精度/标度约束、text/json 默认值拦截、datetime `CURRENT_TIMESTAMP` 特例);**allowLossy 重发流程**(识别「需要 allowLossy」的 400 → 确认后同 operationId 重发,body 仅 `allowLossy` 变化);**`VITE_BAAS_PATH` 前缀拼接**(默认 `/baas` 与 boot 覆盖 `/admin`);detail 静态子路由注册与守卫回归(并入现有 guard/menuPaths spec 覆盖);**侧边栏前缀高亮**(`/baas/projects/:ref` 详情路由下父菜单 `/baas/projects` 高亮、`/baas/projects-x` 类同前缀平级路径不误亮);**`allowed_origins` 通配/白名单往返**(`ProjectVO` 回 `null` → 通配开关开启、回 `[]` → 开关关闭且空列表、编辑提交值与开关态一致,不把通配存成 deny-all);**建表 body 剔除 id 行**(只读 id 行不进 `columns`,POST body 断言无 `id` 列定义);**409 分类提示**(锁忙 409 → 「刷新重试」文案、指纹/有损/唯一键等非锁 409 → 直显后端 msg,不误导重试);**创建响应双 key 展示**(建项目弹窗同时展示 publishable 与 secret 明文并可复制)。`npm run lint` / `npm run build`(vue-tsc)/ `npm run test:unit -- run` 三门禁全绿;依赖变更须干净环境 `npm ci` 验证
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

| 计划 | 范围 | 对应 spec 章节 | 依赖 | 状态(2026-07-22) |
|---|---|---|---|---|
| **A 项目底座与生命周期** | 模块骨架(`ai-work-baas`,端口 4010)、元数据库、AES-GCM 加密器、API Key 体系、项目状态机与延迟清理、项目连接池注册表、Studio 项目管理 API(含 IDOR 防护) | §4、§6、§8.1、§9.1/9.3、§10、§12.1 | — | **已实现**(PR #13 已合入 develop) |
| **B 表管理与 DDL** | 建/改/删表(元数据 + 真实 DDL,改表为全能档:加/删列、改类型/长度、重命名列/表,含 `allowLossy` 确认与类型兼容矩阵)、Redis 串行锁 + 操作 ID 幂等(`baas_ddl_log`)、表状态机(§9.5)、表级 ACL 与 owner 列配置(bigint 校验/自动建索引/anon.insert 要求可空)、软删 tombstone 与同名禁重建、`information_schema` 对账 | §7.3(表管理契约)、§8.2/8.3(配置面)、§9.2/9.4/9.5、§13(表编辑器边界与兼容矩阵) | A | **已实现**(PR #14 已合入 develop,2026-07-20;Codex review 循环收敛) |
| **C 数据面 REST** | PostgREST 风格解析器与 SQL 构建器、`/rest/v1/{table}` 动态 CRUD 语义细则、ApiKeyAuthFilter + URL/apikey/JWT 三方一致性、**终端用户 JWT 完整验签(kid 双版本;签发属 D)**、owner 行策略注入、CORS Filter(先于鉴权、仅查元数据)、错误体映射、资源限制、Cloud/Boot 双形态入口落地、数据面静态 OpenAPI | §5、§7.1/7.4/7.5、§8.2/8.3(执行面)、§11、§12.2、§13 | A、B | **已实现**(PR #15 已合入 develop,2026-07-22) |
| **D 终端用户 Auth** | `/auth/v1/*`:signup(注册即登录)/login、`_sessions`/`_refresh_tokens`、refresh 行锁事务 + 10 秒 grace 加密重放、logout/改密(需 currentPassword)撤销会话、JWT 签发(kid 双版本;**验签已在 C 实现**)、常规/紧急轮换端点、防暴力限速、**Studio 终端用户管理(列表/软删/恢复)**、**`_users.deleted_at` 软删列与系统表 manifest v3 迁移**、grace 密文与过期 token 清理任务 | §7.2、§7.3(终端用户管理细则)、§7.6、§6.1/6.2、§9.1(manifest v3)、§12.2 | A、C | **已实现**(PR #16 已合入 develop,2026-07-23) |
| **E Studio 前端** | ai-work-ui 的 BaaS 控制台(纯前端 + 菜单种子,不改后端):项目列表/详情、可视化表结构编辑器、ACL 与 owner 配置、API Key 管理(明文仅显示一次)、终端用户管理(列表/软删/恢复)、JWT 常规/紧急轮换、手动对账触发、`allowed_origins` 配置;不含表数据浏览与 `system-tables/migrate` 入口,遵循 ai-work-ui/DESIGN.md | §7.3 的界面化(设计见 §7.7) | A、B、D | 设计细化完成(v37,2026-07-23),计划未写 |

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
   - Plan A 的 `ProjectProvisioner.CREATE_DATABASE_SQL` 已显式 `DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci`,与 §13 物理基线一致;部署前置条件 `innodb_page_size=16KiB`(§13 varchar 键长校验按此计算,§9.1 要求启动/初始化时查 `@@innodb_page_size` fail-closed)
   - Plan A 系统表建表语句(`CREATE_USERS/SESSIONS/REFRESH_TOKENS_TABLE_SQL`)仅显式 ENGINE、未带 charset/collation/ROW_FORMAT,且 **id/user_id/session_id/replacement_token_id 均为 `bigint unsigned`**,与 §6.2「signed bigint + Java Long」矛盾;Plan B 须改建表模板并迁移存量项目库统一为 signed,同时补系统表物理基线回读(§9.1)
   - 平台全局 `JacksonConfiguration`(ai-work-common-core)把所有 Long 序列化为 String,`ai-work-common-xss` 对所有 String 反序列化做清洗;数据面必须用独立 ObjectMapper 绕开(§7.5),否则 bigint 变字符串、用户写入的数据被静默改写
   - `BaasStudioExceptionHandler` 的 `@RestControllerAdvice` 限定 `basePackages="com.aiwork.baas.controller"`;数据面 controller 必须放独立包(`com.aiwork.baas.data`)并配独立 advice,否则错误体被接管成 `R` 格式
   - `ProjectDataSourceRegistry` 为回调式借用 API(`execute(BaasProject, Function<DataSource, T>)`,无按 ref 直接取 DataSource 的方法),且**未设置 JDBC queryTimeout**,数据面执行层须自设 5 秒;runtime 密码解密 AAD 为 `{projectId}:db_password:{projectId}`
   - `ProjectKeyService` 没有按 keyHash 查询的方法(Plan C 新增);`ApiKeyGenerator.sha256Hex/matches`(常量时间比较)可直接复用
   - Nacos 种子(`db/ai_work_config.sql`)当前没有任何 `ai-work-baas-*.yml` 条目,cloud 形态元数据源无配置——Plan C 须新增种子;网关路由种子在同文件的 `ai-work-gateway-dev.yml`
   - boot 形态 baas 元数据表并入 `ai_work` 单库(§5 已确认决策):baas 的 mapper 与 Plan A/B 各处注入的 `TransactionTemplate` 全部绑定默认数据源;dynamic-datasource 的 `@DS` 在已开启事务内不切换连接,会使 Plan B fencing 事务写错库,不可用;独立 SqlSessionFactory 方案需改造已合入代码的注入点,均已排除
   - Plan B `DefaultValueRenderer` 的规范时间格式为 `yyyy-MM-dd`(date)与 `yyyy-MM-dd HH:mm:ss`(datetime,严格解析);数据面值解析与输出沿用同一格式(§7.1),不引入 ISO-8601 `T` 分隔变体
   - 资源服务器 ignore-urls 仅授权层 permitAll;`AiWorkBearerTokenExtractor` 默认(`skip-public-url=false`)对公开路径照常抽取 Bearer token 送平台内省,携带项目 JWT 的 `/data/**` 请求会被 401 短路——Plan C 须为 common-security 增加 `skip-resolve-urls`(默认空)并在 Cloud/Boot 配 `[/data/**]`(§5);`aiworkBearerTokenExtractor` bean 无 `@ConditionalOnMissingBean`,不能靠 baas 模块覆盖注入
   - `ai-work-baas/` 目前没有 Dockerfile;cloud compose 的 baas 服务与 boot 一样受 `EnvMasterKeySource` fail-fast 约束,两份 compose 均须注入主密钥(§5)
   - `RegistryConfiguration` 现有项目库 JDBC URL 无 `useCursorFetch` 参数(Connector/J 默认完整物化 ResultSet),`baas.registry.global-max-connections` 默认 200——Plan C 须补 `useCursorFetch=true` 并落实 §7.5 真流式与并发响应信号量(§13)
   - `ApiKeyAuthFilter`(Plan C 已合入)对全部 `/data/**` 统一鉴权:publishable 无 Bearer → ANON,带 Bearer 验签 → AUTHENTICATED,secret+Bearer → 401;Plan D 仅需新增「路径 `/auth/v1/**` 且 secret key → 403」分支。`BaasJwtVerifier.verify` 返回 `VerifiedEndUser(userId, sessionId)`,**但实测 sessionId 为 `String.valueOf(claim)` 宽松转换、验签仅检查该 claim 存在,且 `ApiKeyAuthFilter` 组装 `DataRequestContext(project, role, endUserId)` 时丢弃了 sessionId**——logout 取会话 ID 前,Plan D 须完成 §7.6 三处改造(验签严格 Long 解析、`VerifiedEndUser.sessionId` 改 Long、`DataRequestContext` 增 sessionId 字段)
   - 项目开通时已生成 CURRENT JWT key(`ProjectLifecycleService`:kid = UUID,secret 加密 AAD 为 `{projectId}:jwt_secret:{kid}`,并有单 CURRENT 计数防护)——Plan D 签发器直接消费,无需补开通逻辑
   - `_refresh_tokens` 的 grace 全部列(`consumed_at`/`replacement_token_id`/`reuse_grace_until`/`replay_payload_ciphertext`)Plan A 已建齐且 Plan B manifest 已覆盖,Plan D **不需要**改该表结构;软删只动 `_users`(加 `deleted_at`)
   - `SystemTableManifest` 现实现为 current/legacy **二元**比对(`ExpectedColumn` 仅 currentType/legacyType 两档,`MatchResult` 四值)——Plan D 加列须重构为 §9.1 的三版本链(v1 unsigned / v2 signed / v3 加列),迁移路径 v1→v3、v2→v3
   - 平台 `RedisUtils.get` 用 JDK 反序列化,读取原生 INCR 写入的纯数字值会抛反序列化异常(已知陷阱)——限速计数必须直接用 StringRedisTemplate 原生 INCR/EXPIRE/TTL
   - bcrypt:spring-security-crypto 经 `ai-work-common-security` 传递依赖已在 baas classpath,`BCryptPasswordEncoder` 可直接使用,无需新增依赖
   - `DataPlaneCorsFilter.ALLOW_METHODS`(Plan C 已合入)为 `GET, POST, PATCH, DELETE, OPTIONS`、**无 PUT**——Plan D 改密端点上线须同步补 PUT(§12.2),否则浏览器预检失败
   - `SystemTableMigrationService` 后台扫描默认 `initial-delay` 10 秒、间隔 60 秒、每轮 cursor 批量有界,部署后**不保证即时完成全量迁移**——§9.1 系统表版本准入(`system_table_version`)即为该窗口而设
   - `SystemTableManifest.tableMatches` 按**列数完全相等 + 逐列全等**比对,任何未知列即整表不匹配 → MISMATCH;`SystemTableMigrationService` 的 preflight MISMATCH 会把 ACTIVE 项目置 FAILED——**v2 旧实例遇已迁 v3 的项目即误判致瘫**,Plan D 发布必须执行 §9.1 停服(禁止混部)协议
   - `ProjectAccessService.requireOwned/listVisible`(Plan A 已合入)仅排除 DELETED,会放行 MIGRATING/FAILED/DELETING——Plan D 的 Studio 终端用户端点不能只复用该校验,须按 §9.1 准入显式校验 ACTIVE 且 manifest 当前版
   - `BaasProject` 为 MyBatis-Plus 全字段映射实体:新代码增加 `systemTableVersion` 字段后,若平台库未先加列,`selectById`/`selectList` 一律 Unknown column——**平台库迁移必须先于 v3 实例启动**(§9.1 发布协议②③),镜像内 `db/*.sql` 仅新装环境生效;Plan B 幂等迁移脚本范式(`db/ai_work_baas_plan_b_migration.sql`,`information_schema` 探测、可重复执行)可直接沿用

## 17. 修订记录

- **v39(2026-07-23)**:按 Plan E(Studio 前端)二轮设计评审(codex,3 P2,逐条对照 develop 已合入代码核实)修订。P2——① 建表提交须剔除只读 `id` 行(实测 `TableManagementService.toColumnPlan` 对名为 `id` 的列定义直接 400,含 id 行的 POST 必失败):§7.7 明确 id 行为纯 UI 展示行、不进 `columns`,§14 增建表 body 剔除 id 行断言;② v38 仅保全建项目响应的 secret key,遗漏同为一次性的 publishable key(实测 `CreatedProjectVO` 含 `publishableKey` 与 `secretKey` 双一次性明文、`ApiKeyVO` 后续仅回显前缀):§7.7 改为建项目弹窗同时展示两枚初始 key 并可复制,§14 增双 key 展示断言;③ 409 统一「刷新重试」掩盖可纠正动作(实测 409 覆盖 DDL 锁忙(§9.2)、指纹不一致(§7.3)、有损 ALTER 数据不兼容(§13)、数据面唯一键冲突(§11)多类):§7.7 横切契约改为仅锁忙 409 提示重试、其余非锁 409 直显后端 msg,§14 增 409 分类提示断言。均为纯前端设计细节澄清,不改后端契约与已合入代码。
- **v38(2026-07-23)**:按 Plan E(Studio 前端)设计评审(codex,3 P2,逐条对照 develop 已合入代码核实)修订。P2——① `allowed_origins` 概览编辑若为纯 tag 输入,无法区分通配与拒绝全部两态(实测 `DataPlaneCorsFilter.parseAllowedOrigins` 仅 `null` 为通配、`[]` 为 deny-all,`ProjectVO` 对二者分别回显 `null` 与空数组):§7.7 概览 tab 改为**通配/白名单二态显式切换**,通配提交 `allowedOrigins: null`、空白名单提交 `[]`,前端按 `ProjectVO` 回显还原开关初态,防默认通配项目被静默存成 deny-all;② 项目详情子路由父菜单高亮落空(实测 `AppSidebar.isActive` 为 `route.path === item.path` 精确匹配,`/baas/projects/:ref` 不命中 `/baas/projects`):§7.7 明确须改为**边界安全前缀匹配**(`startsWith(item.path + '/')`,以 `/` 边界避免同前缀平级菜单误亮);③ 「secret key 恒遮挡」与「创建时展示一次明文」冲突(实测 `CreatedKeyVO.plaintext`/`CreatedProjectVO.secretKey` 仅返回一次、库中只存哈希,恒遮挡将使 secret 永久丢失不可用):§7.7 API Key 展示澄清——独立创建与建项目随附的初始 secret key 均在创建响应一次性展示明文,「恒遮挡」仅约束列表与后续查看。§14 Plan E 必测增补侧边栏前缀高亮与 `allowed_origins` 通配/白名单往返两项回归。均为纯前端设计细节澄清,不改后端契约与已合入代码。
- **v37(2026-07-23)**:补齐 Plan E(Studio 前端)设计细化,四项范围/形态决策经需求方确认:① 范围为 §7.3 管理面**准全量**界面化(项目/表结构/ACL/Key/终端用户管理/JWT 轮换/对账/allowed_origins),仅 `system-tables/migrate` 不做 UI(baas_admin 运维走 curl);② **不含表数据浏览**(严格 §7.3 界面化,数据面不接入,留 MVP 后迭代);③ 项目详情页采用**页内 Tabs**(概览/表/API Keys/用户,tab 入 URL query 可直达),不引入二级侧边栏;④ 表结构编辑器为**全屏抽屉**、建/改共用。新增 §7.7 Studio 前端设计:信息架构与路由(upms `sys_menu` 菜单驱动 + 详情页前端静态子路由)、接入层 `VITE_BAAS_PATH` 双形态前缀(默认 `/baas`,boot 覆盖 `/admin`,同 `VITE_AUTH_PATH` 先例)、改表**显式操作意图跟踪**(不做 diff 推导,重命名必须显式)、**allowLossy 前后端协作确认协议**(drop 前端预确认,有损 modify 由后端 400 驱动、同 operationId 重发,前端不复刻 §13 类型兼容矩阵)、危险操作确认分级(紧急轮换/删项目输入 ref、删表输入表名)、Key 明文仅显示一次、operationId 前端生成与重试复用。§14 增补 Plan E 必测项(操作组装/列校验/allowLossy 重发/前缀拼接/路由回归 + 三门禁);§16.1 同步 Plan D 已实现(PR #16 已合入 develop,2026-07-23)与 Plan E 设计细化状态、范围与依赖(A、B、D)。Plan E 为纯前端 + 菜单种子交付,不改后端代码。
- **v36(2026-07-22,补记)**:按 Plan D 四轮设计评审(9 P1 逐条甄别后收敛)修订:§7.2 改密 currentPassword 与 newPassword 同受 8–72 字节校验;§7.3 软删用户 `GET /user`、`PUT /user/password` 返回 401、logout 幂等 204、`/rest` 在 TTL 内仍放行;§14 Plan D 必测追加(软删账户管理 401、currentPassword 73 字节 400、login IP 维度独立触发、并发失败不穿透阈值、auth 请求体 413 流式兜底、网关 GlobalFilter 覆盖 XFF)。**本条为补记**:该轮修订随 commit `c197de1` 落盘时正文已改,但漏更头部状态行与本记录,v37 落盘时一并补齐。
- **v35(2026-07-22)**:按 Plan D 三轮设计评审(1 P1)修订。P1——停服发布协议缺平台库迁移步骤(实测 `BaasProject` 为全字段映射实体,平台库未加 `system_table_version` 列时新代码一切项目查询 Unknown column,影响比「扫描无法补写」更重——数据面鉴权与 Studio 全部不可用;镜像内初始化 SQL 仅新装环境生效,持久化库不随镜像更新加列):§9.1 发布协议固化为四步顺序——① 停全部旧实例(Boot 停旧单体);② 执行幂等 Plan D 平台库迁移脚本 `db/ai_work_baas_plan_d_migration.sql`(沿用 Plan B 范式:information_schema 探测、可重复执行;Cloud `ai_work_baas`、Boot 并库 `ai_work` 同一语句集)加列并回填 0;③ 校验列/类型/默认值后才启动 v3 实例;④ manifest 扫描与发布验收。§14 增「持久化旧平台库升级」测试/验收(Plan C 版 schema 起库 → 迁移 → 断言回填/幂等二次执行/查询与扫描正常,双目标库覆盖,不得只测全新初始化);§6.1 版本列标注迁移脚本指引;§16.2 增补 BaasProject 全字段映射工程事实。按 Plan D 二轮设计评审(3 P1,逐条对照 develop 代码核实)修订;v33 三项自主方案(`system_table_version` 准入、审计 best-effort、限速 fail-open)经需求方裁定全部接受。P1——① v2/v3 实例混部会把已迁 v3 项目打成 FAILED(实测 `SystemTableManifest.tableMatches` 列数全等比对、preflight MISMATCH 置 FAILED,版本准入约束不了滚动发布中的旧实例):§9.1 增 v3 发布协议——内部 Alpha 停服全量替换、禁止混部,无停机场景留两阶段发布(兼容扫描器先行)不实现,附发布验收项与 §14 混部回归;② Cloud 形态可信代理无配置落点(实测 Nacos 种子与 compose 均无该属性,服务恒用网关 remoteAddr,全客户端共享同一 IP 计数器、30 次失败连带封禁全部登录):§5 落地清单增交付物——Nacos 种子含 `baas.auth.trusted-proxies`、compose 网关固定静态 IP 按 /32(最小 CIDR)注入、不得信任整段内网、baas 4010 不映射宿主禁止绕网关直连,Boot 保持空;③ version 写入与 Studio 状态门禁测试闭环:§14 补三条写入路径(开通同事务原子可见、物理 v3 而 version=0 时扫描补写、迁移失败绝不写)与 Studio 用户端点 × MIGRATING/FAILED/DELETING 逐一断言(实测 `ProjectAccessService` 仅排除 DELETED,直接复用会放行三态)。另按裁定附带意见:§12.2 fail-open error 日志补限频/指标告警要求。§16.2 增补 manifest 列数全等混部危害与 ProjectAccessService 状态过滤两条工程事实。
- **v33(2026-07-22)**:按 Plan D 设计评审(1 P0/6 P1,逐条对照 develop 已合入代码核实)修订。P0——refresh token 清理破坏超窗复用检测(超窗即物理删除已消费行,旧 token 重放落入「不存在→401」,§7.2 撤销会话的泄露检测失效):定时清理物理删除范围收窄为「`expire_time` 已过期的行」,已消费未过期行保留至原过期时间、仅清密文(§7.6)。P1——① 系统表版本准入:`baas_project` 增 `system_table_version`(默认 0=未确认;开通/迁移成功/扫描 MATCH_CURRENT 写当前版 3),`/auth/v1/*` 与 Studio 终端用户端点执行前校验 ACTIVE 且 v3,未迁移 fail-closed 明确错误——消除后台扫描(实测默认延迟 10 秒、每轮有界)窗口内的缺列 500(§6.1/§9.1/§7.3/§7.6);② session_id 线协议钉死为 `_sessions.id` 的 JSON number 并严格解析为 Long(实测 Plan C 仅校验存在、`VerifiedEndUser.sessionId` 为宽松 String、`ApiKeyAuthFilter` 组装上下文时丢弃):`VerifiedEndUser.sessionId` 改 Long、`DataRequestContext` 增 sessionId 字段,logout 从上下文取当前会话仅撤销单会话(§7.2/§7.5/§7.6/§16.2);③ CORS 允许方法补 PUT(改密端点预检;Plan C 已合入 `DataPlaneCorsFilter` 须同步修改)(§12.2);④ 限速计数钉死单 Lua 脚本原子「INCR + 首次 EXPIRE + 返回计数与 TTL」,禁两命令分步(崩溃留无 TTL 键致永久封禁)与每次重设过期(退化滑动窗口),Redis 故障 fail-open 记 error 日志(§12.2);⑤ 客户端 IP 判定收紧:仅 remoteAddr 属可信代理列表(`baas.auth.trusted-proxies`,默认空)才读 XFF,Cloud 网关覆盖入站 XFF,Boot 无网关恒用 remoteAddr(§12.2);⑥ 软删/恢复审计跨库语义定稿:项目库事务提交后写审计、best-effort、失败记结构化 error 日志不回滚业务、禁止先审计后业务,不引入 outbox(§7.3)。§14 Plan D 必测项对应增补(清理后超窗重放仍撤销会话、版本准入窗口与状态矩阵、session_id 类型负面与单会话 logout、PUT 预检、限速原子性/边界/故障策略、XFF 伪造、审计失败注入)。§16.2 修正「session_id 已可直接取用」失实条目,增补 CORS 方法清单与迁移扫描节奏两条工程事实。
- **v32(2026-07-22)**:补齐 Plan D(终端用户 Auth)设计细化,五项范围决策经需求方确认:① Studio 终端用户管理端点(列表/删除)归入 Plan D(与 `_users`/`_sessions` 模型同计划落地);② `/auth/v1/*` 仅接受 publishable key,secret key 一律 403;③ signup 注册即登录(响应与 login 同构);④ 改密须提交 currentPassword(access JWT 被窃场景下阻止账户永久接管;MVP 无密码找回);⑤ 终端用户删除定为**软删 + 可恢复**:`_users` 新增 `deleted_at` 列、新增 `POST /users/{userId}/restore` 端点(§7.3 契约扩充),邮箱唯一键不释放、恢复后旧会话不复活。新增 §7.6 终端用户 Auth 执行架构:复用 Plan C 数据面管道与错误出口(ApiKeyAuthFilter 仅增 auth 路径 secret 403 分支)、refresh 行锁事务四分支、JWT 签发器无缓存直查(与验签对称支撑紧急轮换)、BCryptPasswordEncoder cost 10、轮换端点 FOR UPDATE 事务防双 CURRENT、grace 密文与过期 token 清理任务(惰性 + 定时)。§7.2 补鉴权规则/响应形态/改密细则/统一会话撤销语义;§7.3 补终端用户管理细则(软删语义、幂等、审计、存量 JWT 存续注明);§6.2/§9.1 落软删列与 **manifest 版本链 v1→v2→v3**(v1/v2 各有直达 v3 的迁移路径,复用 MIGRATING 检查点机制);§12.2 防暴力限速细化(邮箱/IP 双维度固定窗口阈值、429 + Retry-After、成功清计数、IP 取 X-Forwarded-For 最左、邮箱哈希入键);§3 决策表补注册即登录与软删决策;§14 增补 Plan D 必测项;§16.1 同步 Plan C 已实现(PR #15 已合入 develop,2026-07-22)与 Plan D 设计细化状态;§16.2 增补六条工程事实(ApiKeyAuthFilter 扩展点与 VerifiedEndUser、开通期 CURRENT key 已就绪、_refresh_tokens 列已齐、SystemTableManifest 二元模型重构点、RedisUtils INCR 陷阱、bcrypt 依赖现状)。
- **v31(2026-07-21)**:按 Plan C 三轮复审(1 P1)修订;representation ACL 组合规则确认闭环。P1——「游标/RowCallbackHandler」不构成真流式(Connector/J 默认完整物化 ResultSet,逐行回调只是遍历已物化结果;实测 `RegistryConfiguration` 项目库 URL 无 `useCursorFetch`,全局连接预算默认 200,16 MiB 每请求缓冲理论可放大 3.2 GiB):① 真流式钉死为 `useCursorFetch=true`(注册表 URL,Plan C 补)+ 数据面语句 `TYPE_FORWARD_ONLY/CONCUR_READ_ONLY/fetchSize=100`,不采用 `fetchSize=Integer.MIN_VALUE` 客户端流式(413 中止须排空剩余行,服务端游标可干净提前关闭);② 新增并发响应构建信号量(默认 8,可配):序列化前取许可、finally 必然释放,无许可 429,响应缓冲堆内存钉在许可数 × 响应体上限并与连接预算解耦;临时文件落盘方案因复杂度不值内部 Alpha 明确不采用(§7.5/§13)。§14 增补:真流式配置直接断言(fetchSize/游标参数,仅验 413 无法证明未物化)、信号量耗尽 429 不阻塞、三种出口(成功/异常/413)许可恢复满额。§16.2 补工程事实:现 URL 无 useCursorFetch、global-max-connections 默认 200。
- **v30(2026-07-21)**:按 Plan C 复审(1 P0/1 P1)修订;复审同时确认 v29 五个新决策点(skip-resolve-urls、logout 后 access JWT 存续、decimal 双 token、批量键集合一致、representation 1000 行)全部成立。P0——`return=representation` 构成写权限对 select ACL 的旁路读取(§8.2 四权限独立,update=true+select=false 可借回读取整行):明确 representation 是一次读取,anon/authenticated 使用时必须同时具备对应写权限与 select 权限,检查**前置于任何写入**、不满足 403 且事务零副作用;不带 representation 只要求写权限;service_role 照常绕过(§7.1/§8.2)。P1——行数上限不约束响应字节量(1000 行大 text/json 可达数百 MB,queryTimeout 不覆盖结果集物化与序列化):§13 新增响应体上限 16 MiB(可配);执行层禁止 `queryForList` 全量物化,改为游标逐行读取 + 有界序列化缓冲逐行计数,超限响应提交前 413、representation 场景回滚事务写入不落库(§7.5/§13)。§14 增补:representation 权限前置两例(断言未插入/未更新)、响应体超限三路径(GET/POST/PATCH representation,断言 413 与写入不落库)。
- **v29(2026-07-21)**:按 Plan C 设计评审(2 P0/5 P1,逐条对照代码核实后修订)闭环。P0——① 平台 OAuth 过滤器抢先消费项目 JWT(ignore-urls 仅 permitAll,`BearerTokenAuthenticationFilter` 仍内省 Bearer 头且 `skip-public-url` 默认 false):common-security 新增 `security.oauth2.client.skip-resolve-urls`(默认空,存量行为不变),`AiWorkBearerTokenExtractor` 对匹配路径返回 null,Cloud/Boot 均配 `[/data/**]`;不采用全局 `skip-public-url=true`(boot 单体共享安全链,会改变全部 `@Inner`/ignore-urls 语义),bean 无 `@ConditionalOnMissingBean` 也无法覆盖注入(§5/§16.2);② 验签逐项清单定稿:`alg` 钉死 HS256(拒 none/HS384)、必需 claim `iss/aud/sub/role/session_id/iat/exp`、校验 exp/未来 iat(60 秒时钟偏差)、`exp−iat ≤ 1 小时`;明确 session_id 仅校验存在、数据面不回查 `_sessions`(logout 后 access JWT 在 TTL 内存续,即时失效依赖紧急轮换)(§7.5)。P1——③ DDL 执行屏障消除 TOCTOU:数据请求统一在项目库事务内「预锁取共享 MDL → 锁内重读平台元数据 → 构建执行 SQL → 提交释放」,预锁因重命名/删除失败时重新解析一次;锁外元数据仅作快速阻断与解析依据(§7.5);④ representation 事务算法:PATCH 三步「FOR UPDATE 捕获主键 → 按主键 UPDATE → 按主键回查」,POST 提交前按 generated keys 回查,representation 上限 1000 行(LIMIT 1001 探测超限 400)(§7.1/§7.5);⑤ 线协议矩阵(逻辑类型 × 过滤 token × body token × 绑定 × 响应输出)与 NULL/缺失字段语义(显式 null 恒为 SQL NULL、缺失走默认值)、批量键集合不一致 400、boolean 输出 true/false、json 列输出真实 JSON,其中 decimal 接受 number 与数字字符串双 token 为新决策点(§7.1);⑥ CORS 完整契约:允许 GET/POST/PATCH/DELETE/OPTIONS 与 apikey/Authorization/Content-Type/Prefer 请求头、暴露 Content-Range、Vary 规则(§12.2);⑦ compose 落地补 `ai-work-baas/Dockerfile`(当前缺失),Cloud 与 Boot 两份 compose 均以 `${BAAS_MASTER_KEYS:?}` 注入主密钥、禁止密钥入 Git,验证项补 compose config/镜像构建/readiness 冒烟(§5)。§14 增补对应必测项(平台过滤链隔离、JWT 负面矩阵、TOCTOU 并发、representation 三例、线协议往返、CORS、compose);§16.2 增补两条工程事实(resolver 短路机制与 bean 不可覆盖、Dockerfile 缺失与双 compose 主密钥)。
- **v28(2026-07-21)**:补齐 Plan C(数据面 REST)设计细化,三项范围决策经需求方确认:① JWT 验签完整实现归 Plan C(Plan D 仅签发/会话/轮换端点),消除 §16.1 C/D 行歧义;② boot 单体形态 baas 元数据表并入 `ai_work` 单库(`@DS` 在已开启事务内不切换连接会破坏 Plan B fencing 事务、独立 SqlSessionFactory 需改造已合入代码,均排除);③ 数据面 bigint 以 JSON number 原样输出(独立 ObjectMapper 绕开平台 Long→String 与 XSS 清洗)。新增 §7.5 数据面执行架构:包与序列化隔离、CorsFilter→ApiKeyAuthFilter 管道与 request attribute 上下文、JWT 密钥无缓存直查(支撑紧急轮换立即失效)、元数据每请求直查不缓存(ALTERING 阻断强一致,缓存留二期)、QueryParser/SqlBuilder 两级构建、执行层借用连接池 + 5 秒 queryTimeout + 批量插入单连接手动事务、表状态阻断响应码(DELETED/无表 404,CREATING/ALTERING/FAILED/CONFLICT 403)、静态 OpenAPI 仅仓库交付物。§7.1 补语义细则:值类型感知严格解析绑定(时间格式与 §7.3 默认值一致)、like 不做 `*` 转换、in 切分规则与 `in.()` 400、json 列仅 is 过滤、order 多列、POST/PATCH 禁 `id` 列、默认响应体形态(POST 主键数组/PATCH/DELETE count)、count=exact 二次 COUNT、bigint number 输出。§5 补入口落地清单(网关路由种子、Nacos `ai-work-baas-dev.yml` 种子——当前缺失致 cloud 形态亦无法启动、boot 并库脚本组织与一致性单测、boot ignore-urls、双 compose 环境变量)。§11 补 SQL 超时 500;§12.2 CORS 补 projectRef 不存在无 CORS 头、限流定为 MVP 不新增网关配置;§14 增补 Plan C 必测项;§16.2 增补七条工程事实(全局 Jackson/XSS、异常处理器分包、注册表 API 与 queryTimeout 缺口、key 哈希查询缺口、Nacos 种子缺失、boot 并库依据、时间规范格式);§16.1 同步 Plan B 已实现(PR #14 已合入 develop)、Plan C 设计细化完成。
- **v27(2026-07-18)**:闭环 Plan B 实施计划首轮复审中的管理 API 契约缺口。§7.3 正式加入 `POST /studio/projects/{ref}/system-tables/migrate`:仅 `baas_admin`,非管理员 404,锁忙/非法 manifest/迁移失败 409,同步返回 `status/migrated`,不使用 operationId(依赖项目状态、manifest 与 epoch 检查点幂等);§16.1 同步 Plan B 计划已生成并进入实施前修订状态。
- **v26(2026-07-18)**:四维系统性自查修订(状态机完备性/锁与事务顺序/information_schema 映射完整性/测试覆盖对齐),11 处发现一次性修复。状态机——① FAILED→ACTIVE(重试成功)与 FAILED/CONFLICT→DELETED 出边补全,FAILED/CONFLICT 表允许删除以释放表名,cleanup 改用 DROP TABLE IF EXISTS(§9.5/§9.2);② HTTP 操作陈旧 RUNNING 兜底:请求体不持久化、调度器无法代跑,锁失效超阈值(默认 10 分钟)后 CAS 置 FAILED 并按类型落表状态,同 ID 重试仍可续跑(§9.2);③ MIGRATING 触发入口定为启动后台扫描 + 手动,并参与项目级 epoch(§9.1/§9.2);④ §9.2 锁内分类补 SUCCESS(返回快照)与活跃 RUNNING(409)两类,删表状态集定为 ACTIVE/FAILED/CONFLICT。锁与事务——⑤ 消除 §9.2「GET_LOCK 与 DDL 同连接」与 §9.3「专用管理连接」的矛盾:统一为同一条 Provisioner 数据源物理连接(DDL 本就由 Provisioner 执行,天然不受项目池 drain 波及);⑥ acl-config 补索引分支明确置 ALTERING、纯开关不改表状态(§8.3)。映射——⑦ date/datetime 仅 DATETIME_PRECISION=0 可映射;⑧ int/bigint 显示宽度忽略(int(11) 等价 int,不按字面误拒)(§13);⑨ 对账范围限定 ACTIVE/CONFLICT 表,防「库无表」规则误伤 FAILED/CREATING(§9.4)。§14 增补上述全部测试项。
- **v25(2026-07-18)**:按 Plan B 十七轮复审(3 P1)修订。① 锁内校验先按日志分支分类(§9.2):固定顺序改为「双层锁 → 锁内重读日志分类(无记录/FAILED/陈旧 RUNNING/PENDING)→ 按分支重读结构并校验该分支允许的状态集(新操作仅 ACTIVE、建表重试 CREATING/FAILED、改表重试 ALTERING/CONFLICT、cleanup DELETED 且到期)→ 所有权事务」——统一前置 ACTIVE 会误杀恢复路径,笼统放行又会命中损坏表/tombstone;PENDING 认领改为先锁内复核再 CAS,消除与总顺序的矛盾;② 系统表校验与 signed 迁移闭环(§9.1/§6.2):版本化系统表 manifest(列/类型含 signedness/NULL/默认值/EXTRA/主键自增/索引形状/物理基线)全量比对,精确匹配当前版通过、精确匹配已知历史版(Plan A unsigned 版)自动迁移、其他偏差置 FAILED;项目状态机增 **MIGRATING**(阻断数据面),迁移在项目双层锁下逐表检查点执行,ALTER 前校验 unsigned 数据未超 signed 上限,崩溃按 information_schema 续跑;③ 列 EXTRA 允许集合(§9.4):id 仅 auto_increment、datetime CURRENT_TIMESTAMP 默认值的 DEFAULT_GENERATED 规范化不产生漂移、普通列仅空 EXTRA、on update CURRENT_TIMESTAMP 及其他未建模属性 CONFLICT/REJECTED_IMPORT,对账/导入/续跑共用。§14 增补:四类恢复路径不被误杀、系统表 manifest 与迁移闭环五例、EXTRA 拦截与无漂移。
- **v24(2026-07-18)**:按 Plan B 十六轮复审(3 P1)修订。① 物理前置由声明改为 fail-closed 校验(§9.1):启动/Provisioner 初始化查 `@@innodb_page_size` ≠ 16384 → readiness 失败并禁止建项目与 Plan B DDL(小页面键长上限更低,按 3072 校验会放行必然失败的索引);建库后回读 SCHEMATA 字符集(IF NOT EXISTS 不修正已存在库);系统表 DDL 显式完整物理基线并回读表与字符串列(IF NOT EXISTS 可能命中预存表,且对账跳过 `_` 表无人兜底),不符修正或置 FAILED、不得 ACTIVE;② 依赖现状的校验纳入 §9.2 锁内固定顺序:顺序改为「Redis → GET_LOCK → 再验 Redis → 锁内重读并计算最终结构 → 索引/owner/表状态校验 → 日志所有权事务」,纯 DTO 静态校验可锁外预检但不得作为执行依据(§9.2/§13);③ 类型参数矩阵定稿(§13):int/bigint 无参数、一律渲染 signed;decimal 1≤p≤65、0≤s≤min(30,p);varchar 1≤length≤4096;其余类型无参数;数值默认值须在目标列值域内;UNSIGNED/ZEROFILL 不建模、外部结构拒绝映射;**`_users.id` 定为 signed bigint**——实测 Plan A 系统表为 bigint unsigned 且缺显式基线,Plan B 须改建表模板并迁移存量(§6.2,§16.2 记录改造点)。§14 增补:物理前置三例、锁内重查并发索引上限、类型参数与 signedness 用例。
- **v23(2026-07-18)**:按 Plan B 十五轮复审(2 P1)修订。① 物理基线纳入不变量:全平台唯一基线 utf8mb4 + utf8mb4_general_ci + ROW_FORMAT=DYNAMIC——建库显式携带(与 Plan A CREATE_DATABASE_SQL 一致)、建表语句显式 ENGINE/CHARSET/COLLATE/ROW_FORMAT、列不提供 charset/collation 字段;ACTIVE 准入谓词检查 SCHEMATA、TABLES.TABLE_COLLATION、ROW_FORMAT 及每个 varchar/text 列的 CHARACTER_SET_NAME/COLLATION_NAME,不匹配按既有规则 CONFLICT/REJECTED_IMPORT(排序规则改变 eq/like/unique 比较语义,元数据相同的表行为可能不同)(§9.1/§13/§9.4);② 索引准入矩阵:text/json 的 unique/indexed 一律 400(text BTREE 需前缀、json 需生成列/函数索引,均已禁止);varchar 键长按 utf8mb4 校验 length×4 ≤ 3072(前置 innodb_page_size=16KiB,全列索引 length ≤ 768),modify 扩长已索引列越界同样 400;最终二级索引总数 ≤ 64;全部在进入日志所有权事务前按最终结构校验,不过不执行 DDL(§13,§16.2 记录页大小前置条件)。§14 增补:物理基线三例、索引准入矩阵四例。
- **v22(2026-07-18)**:按 Plan B 十四轮复审(2 P1)修订。① CHECK 约束纳入拦截:§13 明确 CHECK 不进 MVP(MySQL 8.0.16+ 实际执行已启用 CHECK,影响数据面写入与后续 DDL,元数据模型无法表达);准入谓词查 `TABLE_CONSTRAINTS`/`CHECK_CONSTRAINTS`,存在任何 CHECK(含 NOT ENFORCED)视为不可映射,有元数据 CONFLICT/无元数据 REJECTED_IMPORT(§13/§9.4);② 「单列索引可映射」精确谓词:恰一个 key part、COLUMN_NAME 非空且 EXPRESSION IS NULL、SUB_PART IS NULL、INDEX_TYPE='BTREE'、IS_VISIBLE='YES'、COLLATION='A',NON_UNIQUE 映射 unique 布尔位,其余(前缀/FULLTEXT/不可见/降序/函数索引)一律不可映射;owner 索引不变量(§7.3/§8.3)只认满足谓词的索引(§9.4)。§14 增补:列级/表级 CHECK 两类准入拦截、前缀唯一/不可见/FULLTEXT/DESC/函数索引拦截各一例。
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
