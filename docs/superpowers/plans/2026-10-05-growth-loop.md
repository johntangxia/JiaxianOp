# 增长闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans（本计划由同一会话内联执行）。步骤用复选框跟踪。

**Goal:** 热点内容识别、部门渗透率、告警中心，以及带效果追踪的运营任务，形成"发现 → 行动 → 复盘"的闭环。

**Architecture:** 后端新增 `growth` 包：纯函数（渗透率、热点/潜力、告警规则、任务进度）+ `GrowthQueryRepository` + `GrowthAlert`/`GrowthTask` 实体 + `MetricEvaluator`（复用驾驶舱、下钻、读者仓库的指标）+ `GrowthService` + `GrowthController`；前端新增 `/growth` 页面四个标签页。设计见 [增长闭环设计](../specs/2026-10-05-growth-loop-design.md)。

**Tech Stack:** Spring Boot 3.2 / JPA + JdbcTemplate / Vue 3 + TypeScript + Element Plus + ECharts / JUnit 5 + Vitest。

**必读：沿用的约定与踩坑**：区间半开、日期绑 `Timestamp`；`LIMIT/OFFSET` 必须是校验过的整数字面量；`@Repository` 会包装 `IllegalArgumentException`，参数校验放服务；`ADD COLUMN IF NOT EXISTS` 不支持，新表走 `schema.sql`（`SchemaUpgrader` 会在 Hibernate 前执行它）；跑后端测试前先 `docker stop jiaxian-backend`，测完重跑 `seed-demo.sh`；`el-table` 插槽 `row` 要断言；`el-select` 空字符串当"未选择"。

---

### Task 1: 表结构、实体与回填清理
- Modify: `schema.sql`（`growth_alert`、`growth_task`）、`HistoricalBackfillService`（清空两张表）
- Create: `growth/GrowthAlert.java`、`GrowthTask.java`、`GrowthAlertRepository`、`GrowthTaskRepository`
- Test: `GrowthTablesIT`（存取、唯一键）；`HistoricalBackfillServiceIT` 扩展断言
- [ ] 实现并通过，提交。

### Task 2: 纯函数
- Create: `DepartmentPenetrationBuilder`、`HotContentDetector`、`AlertRules`、`TaskMetric`、`TaskProgress` 及 record
- Test: 对应的 `*Test`
- [ ] 先写测试再实现，提交。

### Task 3: 查询与指标评估
- Create: `GrowthQueryRepository`（内容窗口 PV、频道对应、流失预警博主、对象存在性）、`MetricEvaluator`
- Test: `GrowthQueryRepositoryIT`、`MetricEvaluatorTest`
- [ ] 实现并通过，提交。

### Task 4: 服务与接口
- Create: `GrowthService`、`GrowthController`、`GrowthExceptionHandler`、异常类
- Test: `GrowthServiceTest`、`GrowthControllerIT`、`GrowthEndToEndIT`
- [ ] 通过后跑完整 `mvn test`（后台），提交。

### Task 5: 前端数据层
- Create: `api/growth.ts`、`api/types.ts` 增补、`utils/growth.ts`（任务预填、状态文案、进度）、`charts/taskEffectOption.ts` 及测试
- [ ] `npx vitest run` 通过，提交。

### Task 6: 前端页面与默认值
- Create: `views/growth/GrowthView.vue` 及四个标签组件、任务对话框和抽屉；Modify: `router/index.ts`、`App.vue`
- [ ] `npm run build` 通过，提交。

### Task 7: 真实环境验证与文档
- [ ] 全量测试、重建全栈、`seed-demo.sh`（1000 人）、`smoke-test.sh`（加增长闭环检查）、浏览器走查整条闭环。
- [ ] 设计文档改为"已实现"并写偏差；主设计文档、README、记忆更新；提交。
