# 频道运营驾驶舱 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans（本计划由同一会话内联执行）。步骤用复选框跟踪。

**Goal:** 提供综合频道和领域频道共用口径的运营驾驶舱：分发指标、领域频道自主分发单列、来源构成、环比、自动诊断，以及频道详情页。

**Architecture:** 后端新增 `channel` 包：`ChannelQueryRepository`（JdbcTemplate 聚合查询）→ `ChannelCockpitService`（装配当期/上期、基准中位数、调用纯函数 `ChannelDiagnoser`）→ `ChannelCockpitController`。前端新增 `views/channels/*`、`api/channels.ts`、图表配置和指标工具函数。设计见 [频道驾驶舱设计](../specs/2026-10-05-channel-cockpit-design.md)。

**Tech Stack:** Spring Boot 3.2 / JdbcTemplate / OpenGauss 5.0（A 兼容）/ Vue 3 + TypeScript + Element Plus + ECharts / JUnit 5 + Vitest。

**必读：沿用的约定与踩坑**：日期参数绑定 `Timestamp`，区间一律半开 `[from, to+1天)`；`LIMIT` 用校验过的整数字面量；集成测试夹具用 2019 年和中文名前缀；跑后端测试前先 `docker stop jiaxian-backend`；每个任务后跑完整测试（后端在后台跑）；前端 `el-select` 空字符串当"未选择"、`el-table` 插槽里的 `row` 要断言类型。

---

### Task 1: 指标数据结构与诊断（纯函数）
- Create: `channel/` 下的 record：`DistributionMetrics`、`SourceMix`、`ContentMetrics`、`SupplyMetrics`、`CompositeCoverage`、`ChannelPeriod`、`Diagnosis`、`ChannelRow`、`Cockpit`；`ChannelDiagnoser`、`Medians`
- Test: `ChannelDiagnoserTest`
- [ ] 先写测试（每条规则触发/不触发、数据量不足不触发、中位数忽略无数据频道），再实现，提交。

### Task 2: 查询仓库
- Create: `channel/ChannelQueryRepository.java`
- Test: `ChannelQueryRepositoryIT`（2019 夹具）
- [ ] 实现分发、内容侧、互动、供给、集中度、综合覆盖、趋势、构成、Top 内容查询，测试通过，提交。

### Task 3: 服务、接口与异常处理
- Create: `ChannelCockpitService`、`ChannelDetailService`（或合并）、`ChannelCockpitController`、`InvalidChannelQueryException`、`ChannelNotFoundException`、`ChannelExceptionHandler`
- Test: `ChannelCockpitServiceTest`（Mockito，周期与基准装配）、`ChannelCockpitControllerIT`
- [ ] 实现并通过，提交。

### Task 4: 后端端到端
- Test: `ChannelCockpitEndToEndIT`（回填后对账：分发点击 = 入口来源 PV，行数 5 + 23，诊断结构）
- [ ] 通过后跑完整 `mvn test`，提交。

### Task 5: 前端数据层与图表
- Create: `api/channels.ts`、`api/types.ts` 增补、`utils/channelMetrics.ts`、`charts/channelTrendOption.ts`、`charts/mixPieOption.ts`，`BaseChart.vue` 注册饼图；对应 `*.test.ts`
- [ ] 先写测试再实现，`npx vitest run` 通过，提交。

### Task 6: 前端页面
- Create: `views/channels/ChannelCockpitView.vue`、`ChannelDetailView.vue`、`DiagnosisTags.vue`、`ChannelTable.vue`；Modify: `router/index.ts`、`App.vue`
- [ ] 实现，`npm run build` 通过，提交。

### Task 7: 真实环境验证与文档
- [ ] 全量测试、重建全栈、`seed-demo.sh`、`smoke-test.sh`（加驾驶舱接口检查）。
- [ ] 浏览器走查驾驶舱与详情页，`gsql` 对账。
- [ ] 设计文档改为"已实现"并写偏差；主设计文档加取舍；README 更新；提交。
