# 部署与整合 · 设计方案

- 日期：2026-10-05
- 状态：已实现（实施计划见 [部署与整合](../plans/2026-10-05-deployment.md)）
- 前置依赖：后端（历史回填、Kafka 实时流水线、PV 分析、排行榜、KOL 分层、博主详情、维度接口、积分权重）和前端看板（总览、排行榜、KOL 管理、系统管理）均已完成；基础设施的 `docker-compose.yml`（OpenGauss + Redis + Kafka）已在 Plan 1 建好。

## 1. 背景与目标

主设计文档第 9 节要求：本地/演示环境用 `docker-compose` 一键拉起 OpenGauss + Redis + Kafka + Spring Boot + Vue（Nginx 托管前端静态资源），配置走 `application.yml` + 环境变量，该 compose 文件同时作为正式团队的最小可运行参考环境。本阶段把这件事做完，并补上演示数据脚本、冒烟测试和项目 README。

## 2. 范围

**包含**：后端和前端镜像；在现有 compose 里加两个应用服务；Kafka 双监听；健康检查与启动顺序；环境变量与示例文件；演示数据脚本；冒烟测试脚本；根目录 `README.md`；在本机真实构建、拉起全栈并验证。

**明确不包含**（第 10 节）：CI/CD、Kubernetes 清单、HTTPS、鉴权、监控告警、数据库迁移工具。

## 3. compose 布局

沿用现有 `docker-compose.yml`：

- **基础设施**（`opengauss`、`redis`、`kafka`）不加 profile，`docker compose up -d` 的行为和现在完全一致——后端测试和本地开发流程不变。
- **应用服务**（`backend`、`web`）都带 `profiles: ["app"]`，`docker compose --profile app up -d --build` 一条命令拉起全栈。
- **访问入口**：浏览器访问 `http://localhost:${WEB_PORT:-8090}`（8080 在开发机上常被无关进程占用，所以默认 8090）；后端另外映射到宿主机 `${API_PORT:-8081}`，方便直接 `curl` 调管理接口，经 Nginx 也能访问。
- 所有服务 `restart: unless-stopped`；OpenGauss 数据卷保留，Kafka 增加数据卷。

## 4. Kafka 双监听

现在 Kafka 只对外宣告 `localhost:9092`，容器里的后端连不上。改成两个监听器：

| 监听器 | 地址 | 用途 |
|---|---|---|
| `INTERNAL` | `kafka:29092` | 容器内部（后端），也用于 Broker 之间通信 |
| `EXTERNAL` | `localhost:9092` | 宿主机（IDE、`mvn test`） |

宿主机侧的地址不变，现有测试照常使用 `localhost:9092`。容器会因配置变化被重建，主题由应用的 `NewTopic` 启动时自动创建，不会丢东西。

## 5. 健康检查与启动顺序

| 服务 | 检查 |
|---|---|
| `opengauss` | `gsql` 执行 `select 1`（首次启动要等几十秒，设较长的 `start_period` 和重试次数） |
| `redis` | `redis-cli ping` |
| `kafka` | `kafka-topics.sh --bootstrap-server localhost:9092 --list` |
| `backend` | `curl -f http://localhost:8080/api/health`（运行镜像里装 `curl`） |

依赖：`backend` 等 `opengauss`、`redis`、`kafka` 都健康后启动（`depends_on.condition: service_healthy`）；`web` 等 `backend` 健康后启动。

## 6. 镜像

**后端**（`backend/Dockerfile`，构建上下文是 `./backend`，`pom.xml` 和 `src/` 都在里面）：

- 两阶段：`maven:3.9-eclipse-temurin-17` 打包（`-DskipTests`，用 BuildKit 缓存挂载 `~/.m2` 避免重复下载依赖），`eclipse-temurin:17-jre-jammy` 运行；运行镜像里安装 `curl` 供健康检查。
- 入口 `java $JAVA_OPTS -jar app.jar`，`JAVA_OPTS` 默认 `-XX:MaxRAMPercentage=75`，可通过环境变量覆盖。
- 配置全部走已有的环境变量：`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`REDIS_HOST`、`KAFKA_BOOTSTRAP_SERVERS`；容器里分别是 `jdbc:postgresql://opengauss:5432/postgres`、`redis`、`kafka:29092`。
- `backend/.dockerignore` 排除 `target/`。

**前端**（`frontend/Dockerfile`，上下文 `./frontend`）：

- 两阶段：`node:22-alpine` 里 `npm ci && npm run build`，产物复制到 `nginx:1.27-alpine`。
- `frontend/nginx.conf`：
  - 站点根目录是构建产物；`try_files $uri $uri/ /index.html` 让 SPA 深层路径（如 `/kol/12`）回退到首页；
  - `/assets/` 下哈希命名的文件长期缓存（`immutable`），`index.html` 不缓存；
  - 开 gzip（JS、CSS、JSON、SVG）；
  - `/api/` 反向代理到 `http://backend:8080`，`proxy_read_timeout 600s`（历史回填一次要一两分钟），并传递 `Host`、`X-Real-IP`、`X-Forwarded-For`、`X-Forwarded-Proto`。
- `frontend/.dockerignore` 排除 `node_modules/`、`dist/`。

## 7. 配置

- 根目录 `.env.example` 列出全部可配置项和默认值：`GS_USERNAME`、`GS_PASSWORD`、`WEB_PORT`、`API_PORT`、`JAVA_OPTS`。真正的 `.env` 不提交（加入 `.gitignore`）。
- compose 里数据库口令只定义一处（`GS_PASSWORD`），OpenGauss 和后端的 `DB_PASSWORD` 都引用它。
- 默认口令 `Jiaxian@2026` 只适合演示，文档里提醒部署前修改。

## 8. 演示数据脚本与冒烟测试

- **`scripts/seed-demo.sh`**：通过 `API_URL`（默认 `http://localhost:8081`）调用后端：历史回填（默认参数与页面一致：50 员工、20 账号、团队比例 0.25、内容数可通过 `CONTENT_COUNT` 覆盖，默认 300，画像开，结束日期 = 今天往前 30 天，开始日期 = 结束日期往前 18 个月的 1 号）→ PV 汇总重算 → 榜单快照重算 → KOL 重算（统计日取 PV 数据最后一天往前 30 天，12 周）。每一步失败即退出并打印原因。
- **`scripts/smoke-test.sh`**：在全栈已拉起并灌入演示数据后执行，通过 `WEB_URL`（默认 `http://localhost:8090`）检查：首页 200；深层路径 `/kol` 200（SPA 回退生效）；经 Nginx 访问 `/api/health` 返回 `OK`；趋势、Top N、排行榜（月榜及周期列表）、KOL 名单、积分权重、文案库、维度接口都返回 200 且数据非空；非法参数返回 400。任何一项失败就以非零码退出并说明哪一项。
- 两个脚本只依赖 `bash`、`curl`、`date`、`python3`（解析 JSON）。

## 9. 文档

根目录新建 `README.md`：项目是什么、架构图、目录结构；三种运行方式（开发：基础设施 + 本地后端 + Vite；全栈容器；只跑测试）；配置项表；演示流程（回填 → 重算 → 各页面看什么）；常见问题（Colima 没启动、端口占用、OpenGauss 首次启动慢、回填耗时、Vite 开发服务器第一次加载时的 404）；设计文档和实施计划索引；已知限制。

## 10. 已知限制（写进文档）

- 所有 `/api/admin/**` 接口（包括会清空数据的历史回填）没有鉴权，这是整个项目一直约定的范围——必须部署在内网，不能暴露到公网。
- 默认口令只是演示用。
- 单实例部署，不做高可用、HTTPS 和日志收集。

## 11. 验证

在本机 Colima 里完整走一遍：构建两个镜像；`docker compose --profile app up -d` 拉起全栈并等所有服务健康；跑 `seed-demo.sh` 和 `smoke-test.sh`；在内置浏览器里打开 `http://localhost:8090` 检查各页面能正常显示数据；最后确认只拉起基础设施（`docker compose up -d`）时 `mvn test` 仍然全过，验证 Kafka 双监听没有破坏现有流程。

## 12. 明确不做的范围（Out of Scope）

- CI/CD 流水线、Kubernetes 清单。
- HTTPS 证书与域名。
- 鉴权与权限控制。
- 监控、告警、日志收集。
- 数据库迁移工具（沿用 `schema.sql` / `data.sql` 的幂等启动初始化）。
