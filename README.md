<p align="center">
 <img src="https://img.shields.io/badge/AI%20Work%20Platform-4.0-success.svg" alt="AI Work Platform">
 <img src="https://img.shields.io/badge/Spring%20Cloud-2025.1-blue.svg" alt="Spring Cloud">
 <img src="https://img.shields.io/badge/Spring%20Boot-4.0-blue.svg" alt="Spring Boot">
 <img src="https://img.shields.io/badge/Vue-3.5-blue.svg" alt="Vue">
</p>

## 系统说明

- AI Work Platform 是基于 Spring Cloud、Spring Boot、OAuth2 的 RBAC 企业级快速开发平台，同时支持微服务架构和单体架构。
- 认证中心基于 Spring Authorization Server 落地生产级 OAuth2 实践，支持授权码、密码、刷新令牌等常见登录与授权场景。
- 当前版本保留认证、网关、用户权限、BaaS、监控、代码生成和定时任务等核心能力，移除了商业版中的多租户、数据权限、动态路由、流程、支付、公众号、报表和移动端服务等扩展模块。
- 提供 Docker Compose 本地编排，支持快速启动 MySQL、Redis、Nacos 和业务服务。

## 使用文档

本项目基于开源脚手架 pig4cloud 整体改名而来（Maven 坐标、Java 包名、模块目录均已迁移至 `ai-work` 命名体系）。原始脚手架的部署与开发文档可参考：[wiki.pig4cloud.com](https://wiki.pig4cloud.com)，涵盖开发环境配置、服务端启动、前端运行、微服务部署和单体部署等关键步骤。

## 快速开始

### 基础环境

- JDK 17+
- Maven 3.9+
- Docker 和 Docker Compose
- Node.js 16+（运行 `ai-work-ui` 前端时需要）

### 公共基础设施栈（前置）

MySQL、Redis、Nacos 由跨项目共享的公共基础设施栈提供（`~/docker-data/infra/docker-compose.yml`）。该栈供多个项目复用，因此不随本仓库编排、也不纳入本仓库版本控制；新环境按下面三步一次性搭建，本节即其完整定义。

**第 1 步：创建公共网络（一次性）**

```bash
docker network create --driver bridge \
  --subnet 172.28.0.0/16 --ip-range 172.28.1.0/24 dev-infra-net
```

> 网段必须是 `172.28.0.0/16`：网关钉死在 `172.28.0.10`，BaaS 的信任代理白名单依赖该地址。
>
> `--ip-range` 把动态分配池圈到 `172.28.1.0/24`，让 `172.28.0.10` 不进入动态池。只给 `--subnet` 时 Docker 从 `172.28.0.2` 起顺序分配，公共栈 3 个容器加业务栈 6 个动态容器正好顶到 `.10`；一旦被抢占，网关启动会直接失败（`Address already in use`），而改网关 IP 又会让 `BAAS_TRUSTED_PROXIES` 失效。

**第 2 步：准备目录与 Nacos 配置**

在本仓库根目录执行：

```bash
mkdir -p ~/docker-data/infra/nacos/conf
cp ai-work-register/src/main/resources/application.properties ~/docker-data/infra/nacos/conf/
```

> 该文件会整份挂载进 `dev-nacos` 覆盖镜像默认配置，直接复制即可、无需改动：其 `db.url.0` 默认已指向 `dev-mysql` 的 `ai_work_config` 库。

**第 3 步：写入 `~/docker-data/infra/docker-compose.yml`**

<details>
<summary>展开完整内容</summary>

```yaml
# 公共基础设施栈 - 跨项目共享
# 前置：已创建 dev-infra-net 网络；宿主 3306 端口空闲（注意与其他项目的 mysql 容器冲突）
services:
  dev-mysql:
    # 官方 Oracle mysql-server 镜像（经阿里云 mirror，国内拉取快）
    image: registry.cn-hangzhou.aliyuncs.com/dockerhub_mirror/mysql-server:8.0.32
    container_name: dev-mysql
    restart: always
    environment:
      MYSQL_ROOT_HOST: "%"
      MYSQL_ROOT_PASSWORD: root
      TZ: Asia/Shanghai
    # lower_case_table_names 在数据目录初始化后不可变更，删除此行会导致启动失败
    command: --lower_case_table_names=1
    ports:
      - 3306:3306
    volumes:
      - ./mysql/data:/var/lib/mysql
    networks:
      - dev-infra-net

  dev-redis:
    image: registry.cn-hangzhou.aliyuncs.com/dockerhub_mirror/redis
    container_name: dev-redis
    restart: always
    ports:
      # 该 Redis 无密码认证，仅绑回环地址，避免暴露到局域网；
      # 如需局域网访问请改为 6379:6379 并自行加 requirepass
      - 127.0.0.1:6379:6379
    networks:
      - dev-infra-net

  dev-nacos:
    image: nacos/nacos-server:v3.2.2
    container_name: dev-nacos
    restart: always
    depends_on:
      - dev-mysql
    environment:
      MODE: standalone
      TZ: Asia/Shanghai
      # 官方镜像启动脚本强制校验以下三项（缺失直接 exit 255），
      # 即使挂载的 application.properties 里已有 nacos.core.auth.* 也不行。
      # 取值须与 application.properties 保持一致，否则读不了既有 Nacos 数据。
      NACOS_AUTH_TOKEN: VGhpc0lzTXlDdXN0b21TZWNyZXRLZXkwMTIzNDU2Nzg=
      NACOS_AUTH_IDENTITY_KEY: VGhpc0lzTXlDdXN0b21TZWNyZXRLZXkwMTIzNDU2Nzg=
      NACOS_AUTH_IDENTITY_VALUE: VGhpc0lzTXlDdXN0b21TZWNyZXRLZXkwMTIzNDU2Nzg=
    ports:
      - 8848:8848
      - 9848:9848
      - 18080:18080
    volumes:
      # 整份挂载覆盖默认配置，保留 auth 密钥 / 控制台端口 / merged 部署模式等定制
      - ./nacos/conf/application.properties:/home/nacos/conf/application.properties
      - ./nacos/data:/home/nacos/data
      - ./nacos/logs:/home/nacos/logs
    networks:
      - dev-infra-net

networks:
  dev-infra-net:
    external: true
```

</details>

**日常启动**：之后每次启动业务栈前先起公共栈（提供 `dev-mysql` / `dev-redis` / `dev-nacos`）：

```bash
docker compose -f ~/docker-data/infra/docker-compose.yml up -d
```

**全新环境首次启动后导入建库脚本**（数据目录为空时 MySQL 才执行初始化，本仓库的脚本需手工导入）：

```bash
docker exec -i dev-mysql mysql -uroot -proot < db/ai_work.sql
docker exec -i dev-mysql mysql -uroot -proot < db/ai_work_config.sql
docker exec -i dev-mysql mysql -uroot -proot < db/ai_work_baas.sql
```

> Nacos 控制台 `http://localhost:18080`，默认账号 `nacos / nacos`；其配置数据存在 MySQL 的 `ai_work_config` 库，不在容器内。

#### 从旧版本升级（已有数据的安装）

拆分前 MySQL / Nacos 的数据目录由本仓库编排，默认落在仓库内 `./docker-data/infra`（或自定义的 `AI_WORK_INFRA_ROOT` 指向处）；公共栈改用 `~/docker-data/infra`。**两处路径不同**，直接按上面的步骤启动会让 MySQL 以空数据目录重新初始化，原有的业务库、Nacos 配置与 BaaS 数据都读不到（旧目录本身不受影响，仍在原处）。已有数据的安装先迁移数据目录，再启动公共栈：

```bash
# 1. 停掉旧栈。注意：拉取本次改动后，ai-work-mysql / ai-work-redis / ai-work-register
#    已不在 compose 文件里，docker compose down 不会停这几个容器（它们成了 orphan），
#    必须显式停止，否则第 3 步会热拷贝正在写入的 InnoDB 数据文件，拷出损坏的副本。
#    stop 之后还要 rm：这些容器带 restart: always，手动 stop 只压制到守护进程下次重启，
#    之后会被自动拉起，与公共栈抢 3306 / 8848 / 9848 / 18080 端口。
#    数据在宿主机 bind mount 目录里，删容器不会动到数据。
docker compose down                                   # 单体形态：-f docker-compose-boot.yml
docker stop ai-work-mysql ai-work-redis ai-work-register 2>/dev/null
docker rm ai-work-mysql ai-work-redis ai-work-register 2>/dev/null

# 2. 确认旧 MySQL 容器已不存在，再往下走（下面这条应当无输出）
docker ps -a --filter name=ai-work-mysql --format '{{.Names}} {{.Status}}'

# 3. 把旧数据目录复制到公共栈下。OLD_INFRA 是旧数据根目录：默认在仓库内，
#    若此前在 .env 里设过 AI_WORK_INFRA_ROOT，改成它的实际值
OLD_INFRA=./docker-data/infra
mkdir -p ~/docker-data/infra
cp -a "$OLD_INFRA/mysql" ~/docker-data/infra/
cp -a "$OLD_INFRA/nacos" ~/docker-data/infra/         # Nacos 的 raft 数据，配置数据本就在 MySQL 里
```

迁移后跳过上面的建库脚本导入；确认公共栈起来且数据正常后，旧目录可自行删除。

> 若 `dev-infra-net` 是早前用不带 `--ip-range` 的命令建的，建议重建一次：停掉所有接入该网络的容器 → `docker network rm dev-infra-net` → 按第 1 步的新命令重建。公共栈三个容器的 IP 会随之变化，但只有网关的 `172.28.0.10` 被 `BAAS_TRUSTED_PROXIES` 依赖，不受影响。

> 旧数据里 Nacos 的 `application-dev.yml` 把 Redis 地址写死为 `ai-work-redis`，该容器已不存在。迁移后需在 Nacos 控制台把所有含 `ai-work-redis` 的配置改为 `${REDIS_HOST:dev-redis}` 并发布（用控制台而非直接改库，否则不会推送给客户端），可用下面的查询确认已改干净：
>
> ```bash
> docker exec dev-mysql mysql -uroot -proot -N -e \
>   "SELECT data_id FROM ai_work_config.config_info WHERE content LIKE '%ai-work-redis%';"
> ```

### 微服务模式

在项目根目录执行完整编译，再构建并启动本地服务栈：

```bash
mvn clean install -T 4 -Pcloud
(cd ai-work-ui && npm ci && npm run build)
docker compose build && docker compose up
```

服务启动后，默认通过宿主机映射的网关端口 `19999` 访问后端接口（容器内为 `9999`），Nacos 服务端端口为 `8848`、控制台为 `18080`。前端由 `ai-work-ui` 容器以 nginx 静态站提供（镜像只打包宿主机构建好的 `dist/`），浏览器访问 `http://localhost:18000`，其 `/api` 由 nginx 反代到网关。

### 单体模式

单体模式通过 `boot` profile 启用 `ai-work-boot` 模块：

```bash
mvn clean install -T 4 -Pboot
(cd ai-work-ui && npm ci && VITE_AUTH_PATH=/admin VITE_BAAS_PATH=/admin npm run build)
docker compose -f docker-compose-boot.yml build && docker compose -f docker-compose-boot.yml up
```

单体服务容器内监听 `9999` 端口，宿主机映射为 `19999`。前端同样由 `ai-work-ui` 容器提供（宿主机 `18000`），产物须以 `VITE_AUTH_PATH=/admin VITE_BAAS_PATH=/admin` 构建。

## 核心依赖

| 依赖 | 版本 |
| --- | --- |
| AI Work Platform | 4.0.0 |
| JDK | 17+ |
| Spring Boot | 4.0.6 |
| Spring Cloud | 2025.1.2 |
| Spring Cloud Alibaba | 2025.1.0.0 |
| Spring Security OAuth2 Authorization Server | 7.0.5 |
| MyBatis Plus | 3.5.16 |
| Nacos Client | 3.1.2 |
| Druid | 1.2.28 |
| Vue | 3.5.34 |
| Element Plus | 2.13.7 |
| Vite | 5.4.21 |

## 模块说明

```lua
ai-work-ui -- 前端项目（Vue 3 + Vite，随 compose 以 nginx 静态站部署 [80，宿主机映射 18000]）

ai-work-platform
├── ai-work-register -- Nacos Server [8848/9848/18080]（已由公共基础设施栈的 dev-nacos 提供，本模块保留作回退用）
├── ai-work-gateway -- Spring Cloud Gateway 网关 [9999，宿主机映射 19999]
├── ai-work-auth -- 授权服务 [3000]
├── ai-work-upms -- 通用用户权限管理模块
│   ├── ai-work-upms-api -- 通用用户权限管理公共 API
│   └── ai-work-upms-biz -- 通用用户权限业务服务 [4000]
├── ai-work-baas -- BaaS 后端即服务 [4010]
├── ai-work-common -- 系统公共模块
│   ├── ai-work-common-bom -- 全局依赖版本管理
│   ├── ai-work-common-core -- 公共工具类核心包
│   ├── ai-work-common-data -- MyBatis Plus 与缓存扩展
│   ├── ai-work-common-datasource -- 动态数据源封装
│   ├── ai-work-common-log -- 日志服务
│   ├── ai-work-common-oss -- 文件上传工具类
│   ├── ai-work-common-security -- 安全工具类
│   ├── ai-work-common-sentinel -- Sentinel 与异常处理封装
│   ├── ai-work-common-swagger -- 接口文档封装
│   ├── ai-work-common-feign -- OpenFeign 扩展封装
│   ├── ai-work-common-excel -- Excel 导入导出封装
│   └── ai-work-common-xss -- XSS 安全封装
├── ai-work-visual -- 可视化支撑服务
│   ├── ai-work-monitor -- 服务监控 [5001，宿主机映射 15001]
│   ├── ai-work-codegen -- 图形化代码生成 [5002]
│   └── ai-work-quartz -- 定时任务管理台 [5007]
└── ai-work-boot -- 单体模式启动器 [9999，宿主机映射 19999]，通过 `-Pboot` 启用
```

## 配置说明

- 微服务模式使用 `cloud` profile，默认激活 `dev` 环境配置。
- 单体模式使用 `boot` profile，`ai-work-boot` 模块只在该 profile 下参与构建。
- 网关路由由 `ai-work-gateway/src/main/resources/application.yml` 和 Nacos 配置维护，不再依赖动态路由表。
- 默认数据库脚本位于 `db/`，业务表初始化到 `ai_work`，Nacos 配置初始化到 `ai_work_config`。
- 包名已统一为 `com.aiwork`。

## 开源共建

### 开源协议

本项目衍生自 pig4cloud 开源脚手架，遵循 [Apache 2.0 协议](https://www.apache.org/licenses/LICENSE-2.0.html)，允许商业使用，但务必保留类作者、Copyright 信息。

### 其他说明

1. 请基于当前开发分支提交变更，并写清楚改动动机。
2. 提交问题时请写清楚问题现象、开发环境和复现步骤。
3. 代码格式遵循 Spring Java Format，提交前可在项目根目录运行：

```bash
mvn spring-javaformat:apply
```
