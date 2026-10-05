# 下钻与对比 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans（本计划由同一会话内联执行）。步骤用复选框跟踪。

**状态：已全部执行**（偏差见设计文档 5.1 节）。

**Goal:** 频道 → 账号 → 博主 → 内容逐级下钻，和 2–5 个同类对象的对比，口径与频道驾驶舱一致。

**Architecture:** 后端新增 `drill` 包（`SubjectType` + `SubjectQueryRepository` + `DrillService` + `DrillController`），内容侧指标抽成 `ContentMetricsFactory` 由驾驶舱和下钻共用；前端新增账号、博主、内容、对比四个页面并在频道详情加账号表。设计见 [下钻与对比设计](../specs/2026-10-05-drilldown-compare-design.md)。

**Tech Stack:** Spring Boot 3.2 / JdbcTemplate / Vue 3 + TypeScript + Element Plus + ECharts / JUnit 5 + Vitest。

**必读：沿用的约定与踩坑**：区间半开、日期绑 `Timestamp`；`@Repository` 会包装 `IllegalArgumentException`，参数校验放服务；SQL 里的分组列/连接只来自枚举里的固定片段，用户输入只作为绑定参数；`el-table` 插槽 `row` 要断言；跑后端测试前先 `docker stop jiaxian-backend`，测完重跑 `seed-demo.sh`。

---

### Task 1: 共用的内容侧指标工厂
- Create: `channel/ContentMetricsFactory.java`；Modify: `ChannelCockpitService`（改用工厂）
- Test: `ContentMetricsFactoryTest`；既有 `ChannelCockpitServiceTest` 必须保持通过
- [x] 先写测试再抽取，提交。

### Task 2: 分组键与查询仓库
- Create: `drill/SubjectType.java`、`drill/SubjectFilter.java`、`drill/SubjectQueryRepository.java`
- Test: `SubjectQueryRepositoryIT`（2019 夹具）
- [x] 实现 PV/UV/来源、互动、供给、集中度、曝光与停留、关注者、日趋势、Top 内容、对象信息查询，测试通过，提交。

### Task 3: 服务（指标装配、下钻、对比）
- Create: `drill` 下的 record（`SubjectMetrics`、`ExposureMetrics`、`FollowerMetrics`、`DrillTrendPoint`、各页面的响应）、`DrillService`、`InvalidDrillQueryException`、`DrillNotFoundException`
- Test: `DrillServiceTest`
- [x] 实现并通过，提交。

### Task 4: 接口与异常处理
- Create: `DrillController`、`DrillExceptionHandler`
- Test: `DrillControllerIT`、`DrillEndToEndIT`
- [x] 通过后跑完整 `mvn test`（后台），提交。

### Task 5: 前端数据层
- Create: `api/drill.ts`、`api/types.ts` 增补、`utils/drill.ts`（面包屑、最优项、路由 query）、对比图表配置及测试
- [x] `npx vitest run` 通过，提交。

### Task 6: 前端页面
- Create: 账号、博主、内容、对比四个页面和共用组件；Modify: 频道详情（账号表、综合频道领域饼图可点）、`router/index.ts`、`App.vue`
- [x] `npm run build` 通过，提交。

### Task 7: 真实环境验证与文档
- [x] 全量测试、重建全栈、`seed-demo.sh`、`smoke-test.sh`（加下钻/对比检查）、浏览器走查整条下钻路径和对比页。
- [x] 设计文档改为"已实现"并写偏差；主设计文档、README、记忆更新；提交。
