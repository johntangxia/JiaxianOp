# 稼先社区运营分析平台

面向公司内网"稼先社区"（类今日头条的内容社区）的运营分析平台：监控、分析和预测社区的浏览量（PV）和人气，识别头部博主（KOL）并给出运营建议。**这是一个模拟/验证参考项目**——社区本身的数据由内置的模拟器产生，目的是验证分析方案；组件和 `docker-compose.yml` 同时作为正式团队的最小可运行参考环境。

## 能做什么

- **PV 多维分析**：按频道、账号、博主、内容类型切片，日/周/月/年粒度，环比、同比和简单趋势预测（移动平均 + 漂移，带置信区间）。
- **积分排行榜**：员工有"主动行为"和"被动互动"两类积分（发帖、登录、被浏览、被点赞……），月榜/年榜按**当期**积分排名，已结束的周期有归档快照。
- **KOL 管理**：对近 12 个月积分前 1000 名做 RFM 打分和分层（明星 KOL、稳定贡献者、成长潜力新人、流失预警、沉寂用户、观察中），流失预警，运营建议文案库，博主详情（RFM 历史轨迹、活跃时间轴、积分构成）。
- **数据模拟器**：历史批量回填 + 实时事件（Kafka）模拟，带"博主画像"，让每个分层都能在模拟数据里出现。频道体系仿今日头条：5 个综合频道（关注、推荐、热点、视频、直播，只做分发）+ 23 个领域频道（内容只产生在这里）；员工归属某个领域、有"加入社区日期"（新成员陆续加入，加入前没有任何行为），行为带留存分型（常驻/偶尔/淡出）、周末与上班时段规律、浏览热度衰减、同领域偏好和沉默读者（只看不互动）。
- **系统管理**：积分权重（按生效日期版本化）、建议文案库、数据任务（重算汇总/快照/KOL 结果）。

## 架构

```
浏览器 ──▶ Nginx（托管 Vue 静态资源，/api 反向代理）
                      │
                      ▼
                Spring Boot 后端 ──▶ OpenGauss（明细 + 汇总 + 快照）
                      │   ▲
                      │   └── Kafka（实时模拟事件 → 消费者落库、更新 Redis 计数器）
                      └────▶ Redis（今日实时计数器）
```

| 目录 | 内容 |
|---|---|
| `backend/` | Spring Boot 3（Java 17）：模拟器、积分引擎、Kafka 流水线、分析接口 |
| `frontend/` | Vue 3 + TypeScript + Vite + Element Plus + ECharts |
| `scripts/` | 演示数据脚本、冒烟测试脚本 |
| `docs/superpowers/` | 设计文档（`specs/`）和实施计划（`plans/`） |
| `docker-compose.yml` | 基础设施（OpenGauss、Redis、Kafka）和应用（后端、Nginx）的编排 |

## 快速开始：全栈容器

前提：Docker（macOS 上可以用 Colima，先 `colima start`）。

```bash
cp .env.example .env                       # 可选：按需修改口令和端口
docker compose --profile app up -d --build # 构建并拉起全栈（首次构建要几分钟）
scripts/seed-demo.sh                       # 灌入演示数据（历史回填 + 重算，约一两分钟）
scripts/smoke-test.sh                      # 可选：冒烟测试
```

然后打开 http://localhost:8090 。后端也直接暴露在 http://localhost:8081（调管理接口方便）。

停止：`docker compose --profile app down`（加 `-v` 会同时删除数据卷）。

## 开发模式

只拉起基础设施，后端和前端在本机跑：

```bash
docker compose up -d                                                       # OpenGauss + Redis + Kafka
cd backend && mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8093
cd frontend && npm install && VITE_API_TARGET=http://localhost:8093 npm run dev   # http://localhost:5173
```

> 如果本机 8080 没被占用，后端可以不加 `--server.port`，前端也不用设置 `VITE_API_TARGET`。

## 测试

```bash
docker compose up -d           # 后端的集成测试连真实的 OpenGauss、Redis 和 Kafka
cd backend && mvn test         # 后端：单元测试 + 集成测试
cd frontend && npm test        # 前端：纯逻辑和图表配置的单元测试
cd frontend && npm run build   # 前端：类型检查 + 生产构建
```

## 配置

通过环境变量（Docker 下写在 `.env` 里，见 `.env.example`）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `GS_USERNAME` / `GS_PASSWORD` | `jiaxian_app` / `Jiaxian@2026` | 数据库业务账号。**默认口令只适合演示，部署前请修改**（OpenGauss 要求同时包含大小写字母、数字和特殊字符） |
| `WEB_PORT` | `8090` | 前端（Nginx）暴露在宿主机的端口 |
| `API_PORT` | `8081` | 后端暴露在宿主机的端口 |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75` | 后端 JVM 参数 |
| `TZ` | `Asia/Shanghai` | 后端容器时区（后端用系统时区取"今天"） |

后端自身的配置在 `backend/src/main/resources/application.yml`，数据库、Redis、Kafka 地址都可以用环境变量覆盖（`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`REDIS_HOST`、`REDIS_PORT`、`KAFKA_BOOTSTRAP_SERVERS`）；KOL 分析的参数在 `analytics.kol.*`（名单人数、统计窗口、流失预警阈值等）。

## 演示流程

1. 打开 http://localhost:8090/admin/simulator，点"清空并回填"（或运行 `scripts/seed-demo.sh`），再点"一键重算全部"。
2. **总览看板**：切换日/周/月/年，用频道/账号/博主/内容类型筛选；趋势图的虚线和浅色带是预测和置信区间。
3. **积分排行榜**：切换月榜/年榜和周期，看主动/被动积分构成。
4. **KOL 管理**：点击分层卡片筛选；点进一位"流失预警"的博主，看 RFM 历史、标签变化和活跃时间轴的断崖。
5. **系统管理**：修改积分权重（只影响之后新产生的事件）、维护建议文案库、启动实时模拟并观察已发送事件数。

## 常见问题

- **构建时报 `BuildKit is enabled but the buildx component is missing`**：说明本机的 Docker 没有装 buildx。Dockerfile 不依赖 BuildKit 专属特性，用经典构建器即可：`DOCKER_BUILDKIT=0 COMPOSE_BAKE=false docker compose --profile app up -d --build`（或者安装 buildx）。
- **`colima is not running` / 连不上 Docker**：`colima start`，然后等 OpenGauss 和 Kafka 就绪（`docker compose ps` 都是 `healthy`）。
- **端口被占用**：在 `.env` 里改 `WEB_PORT`、`API_PORT`；本机 `8080` 常被别的进程占用，所以默认用了 `8090`/`8081`。
- **OpenGauss 首次启动慢**：初始化要几十秒，`backend` 会等它健康后才启动，耐心等 `docker compose --profile app ps`。
- **历史回填要一两分钟**：整个回填在一个事务里，期间页面按钮显示"回填中…"，数据库里也看不到新数据，完成后才一次性可见。
- **Vite 开发服务器第一次打开某个页面时控制台有一条 404**：开发模式下依赖重新优化造成的，刷新即可，生产构建没有。
- **升级旧库后频道列表里还有"已停用"的频道**：旧种子里的移动无线、固定网络、IT计算被旧内容引用时只会被停用，不会删除；下一次"清空并回填"会一并清掉。总览的频道筛选只列领域频道，不受影响。
- **KOL 页面全是"沉寂"**：模拟数据是历史的，以"今天"为统计日所有人都不活跃了。用控制台的"一键重算全部"，它会自动选一个合适的统计日。

## 已知限制

- 所有 `/api/admin/**` 接口（包括会清空数据的历史回填）**没有鉴权**，这是整个项目一直约定的范围——**必须部署在内网，不能暴露到公网**。
- 单实例部署，不含高可用、HTTPS、日志收集和监控告警。
- 默认口令仅用于演示。

## 设计文档与实施计划

按建设顺序：

| 阶段 | 设计 | 计划 |
|---|---|---|
| 总体设计 | [community-analytics-design](docs/superpowers/specs/2026-09-23-community-analytics-design.md) | — |
| 基础设施与数据模型、历史回填模拟器 | 同上 | [infra-and-data-model](docs/superpowers/plans/2026-09-23-infra-and-data-model.md) |
| 积分引擎与 Kafka 实时流水线 | [points-engine-kafka-pipeline](docs/superpowers/specs/2026-09-23-points-engine-kafka-pipeline-design.md) | [kafka-realtime-pipeline](docs/superpowers/plans/2026-10-04-kafka-realtime-pipeline.md) |
| PV 多维聚合与预测 | [pv-analytics](docs/superpowers/specs/2026-10-04-pv-analytics-design.md) | [pv-analytics](docs/superpowers/plans/2026-10-04-pv-analytics.md) |
| 积分排行榜 | [leaderboard](docs/superpowers/specs/2026-10-04-leaderboard-design.md) | [leaderboard](docs/superpowers/plans/2026-10-04-leaderboard.md) |
| KOL 分层与流失预警 | [kol-segmentation](docs/superpowers/specs/2026-10-04-kol-segmentation-design.md) | [kol-segmentation](docs/superpowers/plans/2026-10-04-kol-segmentation.md) |
| 博主详情页接口 | [blogger-profile](docs/superpowers/specs/2026-10-04-blogger-profile-design.md) | [blogger-profile](docs/superpowers/plans/2026-10-04-blogger-profile.md) |
| 前端：总览与排行榜 | [frontend-dashboard](docs/superpowers/specs/2026-10-04-frontend-dashboard-design.md) | [frontend-dashboard](docs/superpowers/plans/2026-10-04-frontend-dashboard.md) |
| 前端：KOL 与系统管理 | [kol-and-admin-ui](docs/superpowers/specs/2026-10-05-kol-and-admin-ui-design.md) | [kol-and-admin-ui](docs/superpowers/plans/2026-10-05-kol-and-admin-ui.md) |
| 部署与整合 | [deployment](docs/superpowers/specs/2026-10-05-deployment-design.md) | [deployment](docs/superpowers/plans/2026-10-05-deployment.md) |
| 数据基础：频道体系与模拟真实度 | [data-foundation](docs/superpowers/specs/2026-10-05-data-foundation-design.md) | [data-foundation](docs/superpowers/plans/2026-10-05-data-foundation.md) |
