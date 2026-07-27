# 公共基础设施栈拆分 — 设计方案

- 日期:2026-07-27
- 状态:已评审(方案各项决策已与需求方逐项确认)
- 范围:docker 编排与本机联调环境;业务代码仅改 `ai-work-boot` 的连接地址占位符,不动任何业务逻辑

## 1. 背景与目标

当前 `docker-compose.yml` 把 MySQL、Redis、Nacos 与 7 个业务服务定义在同一份编排里。这带来两个问题:

1. **基础设施与项目绑定**。三个容器叫 `ai-work-mysql` / `ai-work-redis` / `ai-work-register`,其他项目无法复用,本机上已经出现同类容器重复(`apk-web-mysql-1` 与 `ai-work-mysql` 争抢宿主 3306 端口,现靠 `docker-compose.override.yml` 打补丁映射到 3307)。
2. **MySQL 镜像含项目耦合**。`db/Dockerfile` 把 `ai_work.sql`、`ai_work_config.sql`、`ai_work_baas.sql` 三个项目种子脚本 COPY 进 `/docker-entrypoint-initdb.d`,镜像本身就是项目专属的。

目标:把 MySQL、Redis、Nacos 拆为跨项目共享的公共基础设施栈,数据与日志统一落在 `/Users/richardhuang/docker-data/infra`,项目栈只保留业务服务。

## 2. 已确认的关键决策

| 决策点 | 结论 |
|---|---|
| 公共栈载体 | `~/docker-data/infra/docker-compose.yml`,纯本机文件,不进任何 git 仓库 |
| Nacos 镜像 | 换官方 `nacos/nacos-server:v3.2.2`(替换现有 pig-mesh 重打包版自建镜像) |
| MySQL 种子 SQL | 完全剥离。公共栈用官方空镜像 + 复用现有已初始化数据目录;`db/*.sql` 留仓库作从零重建材料 |
| 容器命名 | 彻底改中性名 `dev-mysql` / `dev-redis` / `dev-nacos`,**不保留网络别名** |
| MySQL 宿主端口 | `3306`(标准端口归公共基础设施) |
| 网络归属 | 手工 `docker network create`,公共栈与项目栈**两边都** `external: true` |
| `ai-work-register` 模块 | 保留不动,仅从 compose 移除引用,作为官方镜像不兼容时的回退 |

## 3. 目标架构

```
docker network dev-infra-net (172.28.0.0/16, 手工创建)
├── 公共栈  ~/docker-data/infra/docker-compose.yml
│   ├── dev-mysql   mysql:8.0.32              3306:3306
│   ├── dev-redis   dockerhub_mirror/redis    不暴露
│   └── dev-nacos   nacos/nacos-server:v3.2.2 8848 / 9848 / 18080
└── 项目栈  ai-work-platform/docker-compose.yml
    ├── ai-work-gateway  172.28.0.10(固定 IP 保留)
    ├── ai-work-auth / ai-work-upms / ai-work-baas
    └── ai-work-monitor / ai-work-quartz / ai-work-codegen
```

网段保持 `172.28.0.0/16` 不变,因此网关固定 IP `172.28.0.10` 与 BaaS 的 `BAAS_TRUSTED_PROXIES=172.28.0.10/32` 零改动。

数据与日志落点:

| 路径 | 内容 |
|---|---|
| `~/docker-data/infra/mysql/data` | MySQL 数据目录(已初始化,含 `ai_work`、`ai_work_config`、`ai_work_baas` 及 `baas_*` 租户库) |
| `~/docker-data/infra/nacos/{data,logs,conf}` | Nacos raft 数据、日志、挂载的 `application.properties` |
| `~/docker-data/ai-work-platform/logs/*` | 各业务服务应用日志(维持现状) |

## 4. 三条硬约束

实施时任一条违反都会导致启动失败或安全行为退化:

1. **`--lower_case_table_names=1` 必须保留**。MySQL 8 在数据目录初始化后校验该参数,取值变化直接拒绝启动。
2. **网络必须显式指定 `--subnet 172.28.0.0/16`**。手工 `docker network create` 不带 `--subnet` 时 Docker 自行分配网段,网关固定 IP 失效,BaaS 会把网关来源判为不可信并拒绝请求。
3. **宿主 3306 端口冲突**。`apk-web-mysql-1` 当前占用 `127.0.0.1:3306`,公共栈启动前必须先让出该端口(停用该容器或改其映射)。此项涉及另一个项目,由使用者决定处理方式,记入公共栈 README 的启动前置条件。

## 5. 公共栈设计

### 5.1 网络(一次性手工执行)

```bash
docker network create --driver bridge --subnet 172.28.0.0/16 dev-infra-net
```

### 5.2 服务定义要点

- **dev-mysql**:`mysql:8.0.32`,`command: --lower_case_table_names=1`,挂载现有数据目录。因数据目录非空,`/docker-entrypoint-initdb.d` 不会执行,无需任何种子脚本。
- **dev-redis**:沿用现有 `registry.cn-hangzhou.aliyuncs.com/dockerhub_mirror/redis` 镜像,无卷(缓存可丢)。
- **dev-nacos**:`nacos/nacos-server:v3.2.2`,`MODE=standalone`。配置采用**整份挂载 `application.properties`** 方式而非逐条转环境变量——这是 Nacos 官方文档对复杂定制场景的推荐做法,可完整保留现有 auth 密钥、`nacos.console.port=18080`、`nacos.deployment.type=merged` 等定制项。

配置文件来源:复制 `ai-work-register/src/main/resources/application.properties` 到 `~/docker-data/infra/nacos/conf/`,仅修改 `db.url.0` 中的主机名默认值(`ai-work-mysql` → `dev-mysql`),挂载至容器 `/home/nacos/conf/application.properties`。

## 6. 项目侧改动清单

### 6.1 `docker-compose.yml`

1. 删除 `ai-work-mysql`、`ai-work-redis`、`ai-work-register` 三个服务定义
2. `networks` 段改为引用外部网络,各服务引用同步更名;`ai-work-gateway` 的 `ipv4_address: 172.28.0.10` 保留
   ```yaml
   networks:
     dev-infra-net:
       external: true
   ```
3. 各业务服务注入 `MYSQL_HOST=dev-mysql`、`NACOS_HOST=dev-nacos`

### 6.2 `docker-compose-boot.yml`

同样删除 mysql/redis 定义,网络改 external。

### 6.3 `docker-compose.override.yml`

**删除**。该文件存在的唯一目的是避开 3306 端口冲突改映射 3307,公共栈固化 3306 后其理由消失。

### 6.4 `ai-work-boot/src/main/resources/application-dev.yml`

4 处硬编码主机名改为带默认值的占位符,与微服务侧 `${NACOS_HOST:...}` 风格对齐:

| 行 | 现状 | 改为 |
|---|---|---|
| 4 | `host: ai-work-redis` | `host: ${REDIS_HOST:dev-redis}` |
| 14 | `jdbc:mysql://ai-work-mysql:3306/ai_work?...` | `jdbc:mysql://${MYSQL_HOST:dev-mysql}:${MYSQL_PORT:3306}/ai_work?...` |
| 43 | `jdbc:mysql://ai-work-mysql:3306/mysql?...` | 同上模式 |
| 47 | `host: ai-work-mysql` | `host: ${MYSQL_HOST:dev-mysql}` |

### 6.5 Nacos 中的运行时配置(最易遗漏)

各微服务的真实配置存放在 Nacos(即 MySQL `ai_work_config` 库),不在代码仓库中。排查结果不对称:

- **MySQL 地址已是占位符** `${MYSQL_HOST:ai-work-mysql}` → 注入环境变量即可覆盖,配置内容无需改动
- **Redis 地址是硬编码** `host: ai-work-redis` → 必须修改,统一改为 `${REDIS_HOST:dev-redis}` 占位符形式

改动需在两处独立完成,缺一不可:

1. **运行时数据**:经 Nacos 控制台(`localhost:18080`)修改现有配置
2. **种子文件**:同步更新 `db/ai_work_config.sql`,保证全新环境正确

> 二者互不影响:改种子文件不会更新已有运行时数据,反之亦然。

`db/ai_work_baas.sql` 中的 2 处引用均为注释,无需处理。

### 6.6 文档

`README.md`(1 处)、`AGENTS.md`(2 处)中的启动说明同步更新,补充公共栈前置步骤。项目 `.env` 移除 `AI_WORK_INFRA_ROOT`(移交公共栈),保留 `AI_WORK_LOG_ROOT` 与 BaaS 密钥。

## 7. 风险与应对

| 风险 | 说明 | 应对 |
|---|---|---|
| **官方 Nacos 与 pig-mesh 版行为差异** | 现用 `io.github.pig-mesh.nacos:3.2.2` 是 pig 团队为 Boot 4 / JDK 21 重打包版,与官方 `v3.2.2` 不可假定等价;`nacos.console.port=18080`、`nacos.extension.ai.enabled` 等定制项在官方版是否同名生效未经验证 | 保留 `ai-work-register` 模块;实测控制台登录与配置列表读取,不兼容则回退自建镜像 |
| **MySQL 镜像发行版切换** | 从 `mysql-server:8.0.32`(Oracle 发行)换成 `mysql:8.0.32`(Docker 官方发行),复用同一数据目录 | 同版本号下数据目录兼容,但需实测;切换前备份数据目录 |
| **容器改名无别名兜底** | 任何遗漏的 `ai-work-mysql` / `ai-work-redis` / `ai-work-register` 引用都将解析失败 | 按 6.4 / 6.5 清单逐项核对,以联调实测为准 |

## 8. 验证标准

每步须实际跑通,容器 `Up` 不作为通过依据:

1. 建网络 → 起公共栈 → **Nacos 控制台可登录,且能看到原有配置列表**(官方镜像兼容性在此见分晓)
2. `dev-mysql` 可连接,`ai_work` / `ai_work_config` / `ai_work_baas` / `baas_*` 租户库均在
3. 起项目栈 → 各服务在 Nacos 注册成功
4. 网关路由通,BaaS 接口通(验证信任代理白名单未被网络改动破坏)
5. boot 单体形态同样可启动并连通

## 9. 回滚方案

`ai-work-register` 模块与 `db/Dockerfile` 均保留,回滚只需 `git revert` 项目侧改动并启动原 compose。数据目录为两套编排共用,回滚不涉及数据迁移。
