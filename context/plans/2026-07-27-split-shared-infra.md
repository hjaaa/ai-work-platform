# 公共基础设施栈拆分 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 MySQL、Redis、Nacos 从项目 compose 拆出,独立为跨项目共享的公共基础设施栈,项目栈只保留业务服务。

**Architecture:** 手工创建 `dev-infra-net` 网络(172.28.0.0/16),公共栈与项目栈两边均 `external: true` 引用。公共栈位于 `~/docker-data/infra/docker-compose.yml`(不进 git),容器命名为中性名 `dev-mysql` / `dev-redis` / `dev-nacos`,不保留旧名别名。Nacos 换用官方镜像并整份挂载现有 `application.properties`。

**Tech Stack:** Docker Compose、MySQL 8.0.32、Redis、Nacos 3.2.2(官方镜像)、Spring Cloud Alibaba

**设计依据:** [context/specs/2026-07-27-split-shared-infra-design.md](../specs/2026-07-27-split-shared-infra-design.md)

## Global Constraints

- MySQL 启动参数 **必须** 保留 `--lower_case_table_names=1`;数据目录已初始化,该参数变化会导致 MySQL 8 拒绝启动
- 网络 **必须** 显式指定 `--subnet 172.28.0.0/16`;网段变化会使网关固定 IP `172.28.0.10` 失效,进而破坏 BaaS 的 `BAAS_TRUSTED_PROXIES=172.28.0.10/32` 信任代理白名单
- 公共栈启动前宿主 `3306` 端口必须空闲(当前被 `apk-web-mysql-1` 占用)
- 容器不保留网络别名,任何遗漏的 `ai-work-mysql` / `ai-work-redis` / `ai-work-register` 引用都会解析失败
- 公共栈文件位于 `~/docker-data/infra/`,**不纳入 git**;仅项目侧改动产生 commit
- commit message 用简体中文描述,格式 `type(scope): 描述`
- 每步验证以实际连通为准,容器状态 `Up` 不作为通过依据

---

### Task 1: 备份数据目录并创建公共网络

**Files:**
- 无代码改动(纯环境准备)

**Interfaces:**
- Produces: `dev-infra-net` 网络(172.28.0.0/16),供后续所有任务的容器接入;数据目录备份 `~/docker-data/infra-backup-20260727/`

- [ ] **Step 1: 停止项目栈所有容器**

```bash
cd /Users/richardhuang/workspace/ai-work-platform
docker compose down
```

预期:10 个 ai-work-* 容器全部 Removed。

- [ ] **Step 2: 确认容器已全停,数据目录处于一致状态**

```bash
docker ps -a --filter 'label=com.docker.compose.project=ai-work-platform' --format '{{.Names}} {{.Status}}'
```

预期:输出为空,或全部为 `Exited`。若仍有 `Up`,不要继续。

- [ ] **Step 3: 备份 MySQL 数据目录**

数据目录内含 `ai_work`、`ai_work_config`、`ai_work_baas` 及两个 `baas_*` 租户库,是本次改动唯一不可再生的资产。

```bash
cp -a /Users/richardhuang/docker-data/infra /Users/richardhuang/docker-data/infra-backup-20260727
du -sh /Users/richardhuang/docker-data/infra-backup-20260727
```

预期:备份目录大小与源目录一致(数百 MB 量级)。

- [ ] **Step 4: 释放宿主 3306 端口**

```bash
docker stop apk-web-mysql-1
lsof -nP -iTCP:3306 -sTCP:LISTEN
```

预期:`lsof` 无输出,说明 3306 已空闲。

> 若不希望停用 apk-web,改为给 apk-web 换宿主端口后再执行本步验证。

- [ ] **Step 5: 创建公共网络**

```bash
docker network create --driver bridge --subnet 172.28.0.0/16 dev-infra-net
```

- [ ] **Step 6: 验证网段正确**

```bash
docker network inspect dev-infra-net --format '{{range .IPAM.Config}}{{.Subnet}}{{end}}'
```

预期:输出 `172.28.0.0/16`。若不是,删除网络重建 —— 网段错误会在最后一步才以 BaaS 拒绝请求的形式暴露,极难排查。

---

### Task 2: 搭建公共栈的 MySQL 与 Redis

**Files:**
- Create: `~/docker-data/infra/docker-compose.yml`(不进 git)

**Interfaces:**
- Consumes: Task 1 的 `dev-infra-net` 网络
- Produces: 容器 `dev-mysql`(网络内 DNS 名 `dev-mysql`,宿主 3306)、`dev-redis`(DNS 名 `dev-redis`,不暴露宿主端口)

- [ ] **Step 1: 创建公共栈 compose 文件**

写入 `~/docker-data/infra/docker-compose.yml`:

```yaml
# 公共基础设施栈 - 跨项目共享
# 前置条件:
#   1. 网络需先手工创建:
#      docker network create --driver bridge --subnet 172.28.0.0/16 dev-infra-net
#   2. 宿主 3306 端口须空闲(注意与其他项目的 mysql 容器冲突)
# 启动: docker compose -f ~/docker-data/infra/docker-compose.yml up -d
services:
  dev-mysql:
    image: mysql:8.0.32
    container_name: dev-mysql
    restart: always
    environment:
      MYSQL_ROOT_HOST: "%"
      MYSQL_ROOT_PASSWORD: root
      TZ: Asia/Shanghai
    # lower_case_table_names 在数据目录初始化后不可变更,删除此行会导致启动失败
    command: --lower_case_table_names=1
    ports:
      - 3306:3306
    volumes:
      - /Users/richardhuang/docker-data/infra/mysql/data:/var/lib/mysql
    networks:
      - dev-infra-net

  dev-redis:
    image: registry.cn-hangzhou.aliyuncs.com/dockerhub_mirror/redis
    container_name: dev-redis
    restart: always
    networks:
      - dev-infra-net

networks:
  dev-infra-net:
    external: true
```

- [ ] **Step 2: 启动 MySQL 与 Redis**

```bash
docker compose -f ~/docker-data/infra/docker-compose.yml up -d
```

- [ ] **Step 3: 验证 MySQL 真正可用且数据完好**

这是本任务的核心验证 —— 检验官方 `mysql:8.0.32` 镜像能否复用 Oracle `mysql-server:8.0.32` 留下的数据目录。

```bash
docker exec dev-mysql mysql -uroot -proot -e "SHOW DATABASES;"
```

预期:输出包含 `ai_work`、`ai_work_config`、`ai_work_baas`,以及 `baas_dprpxjqbgrhqpqhgloez`、`baas_yglgolupxtnxvfcmscda` 两个租户库。

- [ ] **Step 4: 验证业务表可读**

```bash
docker exec dev-mysql mysql -uroot -proot -e "SELECT COUNT(*) FROM ai_work.sys_user;"
docker exec dev-mysql mysql -uroot -proot -e "SELECT COUNT(*) FROM ai_work_config.config_info;"
```

预期:两条均返回具体行数,不报错。`config_info` 是 Nacos 的配置表,行数应大于 0。

> 若 MySQL 启动失败,先查 `docker logs dev-mysql`。若报 `lower_case_table_names` 相关错误,说明该参数被遗漏;若报数据字典不兼容,则官方镜像发行版不兼容,改用原 `registry.cn-hangzhou.aliyuncs.com/dockerhub_mirror/mysql-server:8.0.32` 镜像(其余配置不变)。

- [ ] **Step 5: 验证 Redis 可用**

```bash
docker exec dev-redis redis-cli ping
```

预期:输出 `PONG`。

---

### Task 3: 公共栈接入官方 Nacos 镜像

**Files:**
- Create: `~/docker-data/infra/nacos/conf/application.properties`(从 `ai-work-register/src/main/resources/application.properties` 复制修改)
- Modify: `~/docker-data/infra/docker-compose.yml`(追加 dev-nacos 服务)

**Interfaces:**
- Consumes: Task 2 的 `dev-mysql`(Nacos 的配置数据存于其 `ai_work_config` 库)
- Produces: 容器 `dev-nacos`(DNS 名 `dev-nacos`,服务端口 8848/9848,控制台 18080)

> **本任务是整个方案的最大风险点。** 现用的 `io.github.pig-mesh.nacos:3.2.2` 是 pig 团队为 Boot 4 / JDK 21 重打包的版本,与官方 `nacos/nacos-server:v3.2.2` 不可假定等价。若验证不通过,按 Step 6 回退。

- [ ] **Step 1: 复制并修改 Nacos 配置文件**

```bash
mkdir -p /Users/richardhuang/docker-data/infra/nacos/conf
cp /Users/richardhuang/workspace/ai-work-platform/ai-work-register/src/main/resources/application.properties \
   /Users/richardhuang/docker-data/infra/nacos/conf/application.properties
```

- [ ] **Step 2: 改写配置中的数据库主机名默认值**

编辑 `~/docker-data/infra/nacos/conf/application.properties`,把 `db.url.0` 一行中的 `${MYSQL_HOST:ai-work-mysql}` 改为 `${MYSQL_HOST:dev-mysql}`。修改后该行为:

```properties
db.url.0=jdbc:mysql://${MYSQL_HOST:dev-mysql}:${MYSQL_PORT:3306}/${MYSQL_DB:ai_work_config}?characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=false&useJDBCCompliantTimezoneShift=true&useLegacyDatetimeCode=false&serverTimezone=GMT%2B8&nullCatalogMeansCurrent=true&allowPublicKeyRetrieval=true
```

其余配置项(auth 密钥、`nacos.console.port=18080`、`nacos.deployment.type=merged` 等)保持原样不动。

- [ ] **Step 3: 在公共栈 compose 中追加 dev-nacos**

在 `~/docker-data/infra/docker-compose.yml` 的 `dev-redis` 之后、`networks:` 之前插入:

```yaml
  dev-nacos:
    image: nacos/nacos-server:v3.2.2
    container_name: dev-nacos
    restart: always
    depends_on:
      - dev-mysql
    environment:
      MODE: standalone
      TZ: Asia/Shanghai
    ports:
      - 8848:8848
      - 9848:9848
      - 18080:18080
    volumes:
      - /Users/richardhuang/docker-data/infra/nacos/conf/application.properties:/home/nacos/conf/application.properties
      - /Users/richardhuang/docker-data/infra/nacos/data:/home/nacos/data
      - /Users/richardhuang/docker-data/infra/nacos/logs:/home/nacos/logs
    networks:
      - dev-infra-net
```

- [ ] **Step 4: 启动并观察日志**

```bash
docker compose -f ~/docker-data/infra/docker-compose.yml up -d dev-nacos
sleep 30 && docker logs --tail 50 dev-nacos
```

预期:日志出现 `Nacos started successfully in stand alone mode. use external storage`。
关键在 `use external storage` —— 若显示 `use embedded storage`,说明挂载的配置未生效,Nacos 会用内嵌 Derby 而非 MySQL,此时看到的配置列表是空的。

- [ ] **Step 5: 验证控制台可登录且能读到原有配置**

浏览器打开 `http://localhost:18080`,用 `nacos` / `nacos` 登录,进入「配置管理 → 配置列表」。

预期:能看到 `ai-work-auth.yml`、`ai-work-upms.yml`、`ai-work-baas.yml` 等原有配置项。

命令行等价验证:

```bash
docker exec dev-mysql mysql -uroot -proot -N -e \
  "SELECT data_id FROM ai_work_config.config_info LIMIT 10;"
```

预期:列出各服务的配置 data_id。若控制台看不到但数据库里有,说明 Nacos 未连上 MySQL,回到 Step 4 检查存储模式。

- [ ] **Step 6: 兼容性不通过时的回退**

若 Step 4/5 失败且排查后确认是官方镜像与 pig-mesh 版的行为差异,把 `dev-nacos` 的 image 换回自建镜像:

```yaml
    image: ai-work-register
    # 该镜像需先在项目仓库执行 mvn clean install -Pcloud 后 docker compose build ai-work-register
```

并去掉 `application.properties` 挂载(自建镜像已内置该配置),保留其余不变。回退后本方案其余部分仍然成立。

---

### Task 4: 修正 Nacos 中硬编码的 Redis 地址

**Files:**
- Modify: `db/ai_work_config.sql`(种子文件)
- Modify: Nacos 运行时配置(经控制台,非文件)

**Interfaces:**
- Consumes: Task 3 的 `dev-nacos` 控制台
- Produces: 各服务配置中 Redis 地址变为 `${REDIS_HOST:dev-redis}`,可被环境变量覆盖

> MySQL 地址在 Nacos 配置里已是占位符 `${MYSQL_HOST:ai-work-mysql}`,注入环境变量即可覆盖,**无需改动**。Redis 地址是硬编码 `host: ai-work-redis`,必须改。
> 运行时数据与种子文件互不影响,两处都要改。

- [ ] **Step 1: 定位所有含 Redis 硬编码的配置**

```bash
docker exec dev-mysql mysql -uroot -proot -N -e \
  "SELECT data_id FROM ai_work_config.config_info WHERE content LIKE '%ai-work-redis%';"
```

预期:列出若干 data_id。记录下来,逐个处理。

- [ ] **Step 2: 经控制台逐个修改运行时配置**

在 `http://localhost:18080` 配置列表中,对 Step 1 列出的每个 data_id 执行:编辑 → 把 `host: ai-work-redis` 改为 `host: ${REDIS_HOST:dev-redis}` → 发布。

> 用控制台而非直接 UPDATE 数据库:Nacos 有配置变更历史与缓存刷新机制,直接改库不会触发客户端推送。

- [ ] **Step 3: 验证运行时配置已无旧引用**

```bash
docker exec dev-mysql mysql -uroot -proot -N -e \
  "SELECT COUNT(*) FROM ai_work_config.config_info WHERE content LIKE '%ai-work-redis%';"
```

预期:输出 `0`。

- [ ] **Step 4: 同步修改种子文件**

编辑 `db/ai_work_config.sql`,把其中的 `host: ai-work-redis` 改为 `host: ${REDIS_HOST:dev-redis}`。

验证种子文件已无旧引用:

```bash
grep -c 'ai-work-redis' db/ai_work_config.sql
```

预期:输出 `0`。

- [ ] **Step 5: 提交种子文件改动**

```bash
git add db/ai_work_config.sql
git commit -m "chore(db): nacos 种子配置的 redis 地址改为可覆盖占位符"
```

---

### Task 5: 改造微服务形态 compose

**Files:**
- Modify: `docker-compose.yml`
- Delete: `docker-compose.override.yml`

**Interfaces:**
- Consumes: Task 1 的 `dev-infra-net`、Task 2/3 的三个公共容器
- Produces: 项目栈仅含 7 个业务服务,经环境变量连接公共基础设施

- [ ] **Step 1: 删除三个基础设施服务定义**

从 `docker-compose.yml` 删除 `ai-work-mysql`(第 12-28 行)、`ai-work-redis`(第 30-35 行)、`ai-work-register`(第 37-52 行)三段。

- [ ] **Step 2: 改写网络定义为外部引用**

把文件末尾的 `networks:` 段整体替换为:

```yaml
networks:
  dev-infra-net:
    external: true
```

同时把每个服务的 `networks: - spring_cloud_default` 改为 `networks: - dev-infra-net`。

`ai-work-gateway` 的写法保留固定 IP,改为:

```yaml
    networks:
      dev-infra-net:
        ipv4_address: 172.28.0.10
```

- [ ] **Step 3: 为业务服务注入基础设施地址**

给 `ai-work-auth`、`ai-work-upms`、`ai-work-baas`、`ai-work-monitor`、`ai-work-quartz`、`ai-work-codegen`、`ai-work-gateway` 各服务补充 environment(已有 environment 的服务追加,如 `ai-work-baas`):

```yaml
    environment:
      MYSQL_HOST: dev-mysql
      NACOS_HOST: dev-nacos
      REDIS_HOST: dev-redis
```

- [ ] **Step 4: 删除 override 文件**

```bash
git rm docker-compose.override.yml
```

该文件存在的唯一目的是避开 3306 端口冲突改映射 3307,公共栈固化 3306 后理由消失。

- [ ] **Step 5: 校验 compose 语法与解析结果**

```bash
docker compose config --quiet && echo "语法 OK"
docker compose config | grep -E 'container_name|ipv4_address|MYSQL_HOST|NACOS_HOST|REDIS_HOST'
```

预期:语法 OK;输出中只有 7 个业务容器(gateway / auth / upms / baas / monitor / quartz / codegen),无 mysql/redis/register;网关 `ipv4_address: 172.28.0.10` 存在;三个环境变量正确注入。

- [ ] **Step 6: 启动项目栈**

```bash
docker compose up -d
sleep 60 && docker compose ps
```

- [ ] **Step 7: 验证服务在 Nacos 注册成功**

```bash
docker logs --tail 30 ai-work-upms 2>&1 | grep -iE 'nacos|register|error'
```

预期:出现注册成功日志,无连接错误。

浏览器 `http://localhost:18080` →「服务管理 → 服务列表」,预期看到 ai-work-auth / ai-work-upms / ai-work-baas 等实例,健康状态正常。

- [ ] **Step 8: 验证网关路由与 BaaS 信任代理**

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:9999/actuator/health
```

预期:`200`。

BaaS 链路验证(经网关访问,检验 `BAAS_TRUSTED_PROXIES=172.28.0.10/32` 在新网络下仍生效):

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:9999/baas/actuator/health
```

预期:`200`。若返回 403 或提示来源不可信,说明网关容器未拿到 `172.28.0.10`,回到 Step 2 检查固定 IP 与网段。

- [ ] **Step 9: 提交**

```bash
git add docker-compose.yml
git commit -m "chore(docker): 微服务栈移除基础设施定义并接入公共网络"
```

---

### Task 6: 改造单体形态

**Files:**
- Modify: `docker-compose-boot.yml`
- Modify: `ai-work-boot/src/main/resources/application-dev.yml`

**Interfaces:**
- Consumes: Task 1/2 的公共网络与 dev-mysql、dev-redis
- Produces: boot 单体形态可脱离项目内基础设施独立启动

- [ ] **Step 1: 改写 boot 形态的硬编码主机名**

`ai-work-boot/src/main/resources/application-dev.yml` 共 4 处,改为带默认值的占位符,与微服务侧 `${NACOS_HOST:...}` 风格一致:

| 行 | 现状 | 改为 |
|---|---|---|
| 4 | `host: ai-work-redis` | `host: ${REDIS_HOST:dev-redis}` |
| 14 | `jdbc:mysql://ai-work-mysql:3306/ai_work?...` | `jdbc:mysql://${MYSQL_HOST:dev-mysql}:${MYSQL_PORT:3306}/ai_work?...` |
| 43 | `jdbc:mysql://ai-work-mysql:3306/mysql?...` | `jdbc:mysql://${MYSQL_HOST:dev-mysql}:${MYSQL_PORT:3306}/mysql?...` |
| 47 | `host: ai-work-mysql` | `host: ${MYSQL_HOST:dev-mysql}` |

URL 的查询参数部分保持原样,只替换主机与端口段。

- [ ] **Step 2: 验证无遗漏**

```bash
grep -n 'ai-work-mysql\|ai-work-redis' ai-work-boot/src/main/resources/application-dev.yml
```

预期:无输出。

- [ ] **Step 3: 改造 boot 形态 compose**

`docker-compose-boot.yml` 删除 `ai-work-mysql`(第 12-26 行)、`ai-work-redis`(第 28-33 行)两段;`networks:` 段替换为 `dev-infra-net: external: true`;`ai-work-gateway` 服务的 networks 引用同步改名,并补充 environment:

```yaml
      MYSQL_HOST: dev-mysql
      REDIS_HOST: dev-redis
```

(保留其已有的 `BAAS_MASTER_KEYS`、`BAAS_ACTIVE_KEY_ID`)

- [ ] **Step 4: 校验语法**

```bash
docker compose -f docker-compose-boot.yml config --quiet && echo "语法 OK"
```

- [ ] **Step 5: 实测单体形态启动**

先停掉微服务栈避免 9999 端口冲突:

```bash
docker compose down
mvn clean install -Pboot -DskipTests
docker compose -f docker-compose-boot.yml up -d
sleep 60 && curl -s -o /dev/null -w '%{http_code}\n' http://localhost:9999/actuator/health
```

预期:`200`。

- [ ] **Step 6: 恢复微服务栈并提交**

```bash
docker compose -f docker-compose-boot.yml down
docker compose up -d
git add docker-compose-boot.yml ai-work-boot/src/main/resources/application-dev.yml
git commit -m "chore(docker): 单体栈移除基础设施定义并改用地址占位符"
```

---

### Task 7: 更新文档与环境变量

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `.env`(本机文件,不进 git)
- Create: `~/docker-data/infra/README.md`(不进 git)

**Interfaces:**
- Consumes: 前六个任务的最终形态
- Produces: 可复现的启动说明

- [ ] **Step 1: 编写公共栈使用说明**

写入 `~/docker-data/infra/README.md`:

```markdown
# 公共基础设施栈

跨项目共享的 MySQL / Redis / Nacos,数据落在本目录。

## 首次使用(一次性)

    docker network create --driver bridge --subnet 172.28.0.0/16 dev-infra-net

网段必须是 172.28.0.0/16:ai-work-platform 的网关钉死在 172.28.0.10,
BaaS 的信任代理白名单依赖该地址。

## 启动前置条件

宿主 3306 端口须空闲。已知冲突:apk-web 项目的 apk-web-mysql-1 容器占用该端口。

## 启动 / 停止

    docker compose -f ~/docker-data/infra/docker-compose.yml up -d
    docker compose -f ~/docker-data/infra/docker-compose.yml down

## 容器与端口

| 容器 | 用途 | 宿主端口 |
|---|---|---|
| dev-mysql | MySQL 8.0.32 | 3306 |
| dev-redis | Redis 缓存 | 不暴露 |
| dev-nacos | Nacos 3.2.2 注册与配置中心 | 8848 / 9848,控制台 18080 |

## 从零重建数据库

数据目录为空时 MySQL 才会执行初始化。ai-work-platform 的建库脚本在其仓库
db/ 目录下,需手工导入:

    docker exec -i dev-mysql mysql -uroot -proot < db/ai_work.sql
    docker exec -i dev-mysql mysql -uroot -proot < db/ai_work_config.sql
    docker exec -i dev-mysql mysql -uroot -proot < db/ai_work_baas.sql
```

- [ ] **Step 2: 更新项目 .env**

`AI_WORK_INFRA_ROOT` 已移交公共栈,从项目 `.env` 中删除该行,保留 `AI_WORK_LOG_ROOT` 与两个 BaaS 密钥变量。

```bash
grep -c 'AI_WORK_INFRA_ROOT' .env
```

预期:输出 `0`。

- [ ] **Step 3: 更新 README.md 与 AGENTS.md**

两处文档中涉及 docker 启动的说明,补充"先启动公共基础设施栈"的前置步骤,并移除对 `ai-work-mysql` / `ai-work-redis` / `ai-work-register` 属于本项目容器的表述。

定位待改位置:

```bash
grep -n 'ai-work-mysql\|ai-work-redis\|ai-work-register\|docker compose' README.md AGENTS.md
```

AGENTS.md 中「构建、测试与开发命令」一节的 docker compose 描述需说明:`ai-work-register` 端口 8848/9848 已由公共栈的 `dev-nacos` 提供。

- [ ] **Step 4: 验证仓库内无遗留的旧主机名引用**

```bash
grep -rn 'ai-work-mysql\|ai-work-redis' --include='*.yml' --include='*.yaml' --include='*.md' . \
  | grep -v node_modules | grep -v target | grep -v context/plans | grep -v context/specs
```

预期:仅剩 `db/ai_work_baas.sql` 中的 2 处注释(无影响),以及 `ai-work-register/src/main/resources/application.properties`(保留模块的内置配置,作回退用)。其余应为空。

- [ ] **Step 5: 提交**

```bash
git add README.md AGENTS.md
git commit -m "docs(infra): 更新公共基础设施栈拆分后的启动说明"
```

---

## 完成标准

全部任务完成后,以下应同时成立:

1. `docker compose -f ~/docker-data/infra/docker-compose.yml ps` 显示 dev-mysql / dev-redis / dev-nacos 三个容器运行中
2. 项目栈 `docker compose ps` 仅显示 7 个业务容器
3. Nacos 控制台 `localhost:18080` 可见全部服务实例与配置
4. `curl localhost:9999/actuator/health` 与 BaaS 健康检查均返回 200
5. boot 单体形态可独立启动并连通
6. 仓库内除保留的回退模块外,无 `ai-work-mysql` / `ai-work-redis` 主机名引用

## 清理(确认稳定后再执行)

以下为不可逆操作,须在方案稳定运行一段时间、确认无需回退后,单独确认再执行:

- 删除数据目录备份 `~/docker-data/infra-backup-20260727/`
- 删除 `ai-work-register` 模块及 `db/Dockerfile`(本次方案明确保留,不在计划范围内)
