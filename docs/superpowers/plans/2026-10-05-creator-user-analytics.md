# 创作者分析与站内用户分析 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans（本计划由同一会话内联执行）。步骤用复选框跟踪。

**Goal:** 创作者总览/排行/成长阶梯/集中度，以及全员的生命周期、价值分层、兴趣与跨领域、用户列表和用户 360。

**Architecture:** 后端新增 `people` 包：纯函数 + `PeopleQueryRepository`（少量新查询，其余复用读者、下钻仓库）+ `CreatorService`/`UserService` + 控制器；前端新增 `/creators`、`/users`、`/users/:id`。设计见 [创作者与用户分析设计](../specs/2026-10-05-creator-user-analytics-design.md)。

**Tech Stack:** Spring Boot 3.2 / JdbcTemplate / Vue 3 + TypeScript + Element Plus + ECharts / JUnit 5 + Vitest。

**必读：沿用的约定与踩坑**：区间半开、日期绑 `Timestamp`；`LIMIT/OFFSET` 必须是校验过的整数字面量；`@Repository` 会包装 `IllegalArgumentException`，参数校验放服务；`el-table` 插槽 `row` 要断言；`el-select` 空字符串当"未选择"；浏览器脚本里 `document.querySelector('.el-table…')` 会选到别的标签页的表，要用 `#pane-xxx` 限定；跑后端测试前先 `docker stop jiaxian-backend`，测完重跑 `seed-demo.sh`。

---

### Task 1: 创作者纯函数
- Create: `people/GiniCalculator`、`CreatorStageClassifier`、`CreatorStage`、`ConcentrationBuilder`、`CreatorRetention`（复用 `CohortBuilder`）、`Medians`
- Test: `CreatorLogicTest`
- [ ] 先写测试再实现，提交。

### Task 2: 用户纯函数
- Create: `people/LifecycleStage`、`LifecycleClassifier`、`TransitionMatrix`、`ValueSegment`、`ValueSegmenter`；`contest/ContestScorer.percentiles` 改为 public
- Test: `UserLogicTest`
- [ ] 先写测试再实现，提交。

### Task 3: 查询仓库
- Create: `people/PeopleQueryRepository`（首次发帖与发帖日、累计行为首次时间、最近一次行为、名下账号新增关注、账号类型拆分、用户时间轴/阅读偏好/关注/积分/最近行为、部门阅读领域）
- Test: `PeopleQueryRepositoryIT`（2019 夹具）
- [ ] 实现并通过，提交。

### Task 4: 创作者服务与接口
- Create: `CreatorService`、`CreatorController`、`PeopleExceptionHandler`、异常类、各响应 record
- Test: `CreatorServiceTest`、`CreatorControllerIT`
- [ ] 通过后提交。

### Task 5: 用户服务与接口
- Create: `UserService`、`UserController`
- Test: `UserServiceTest`、`UserControllerIT`、`PeopleEndToEndIT`
- [ ] 通过后跑完整 `mvn test`（后台），提交。

### Task 6: 前端数据层
- Create: `api/people.ts`、`api/types.ts` 增补、`utils/people.ts`、`charts/peopleOption.ts` 及测试
- [ ] `npx vitest run` 通过，提交。

### Task 7: 前端页面
- Create: `views/creators/*`、`views/users/*`；Modify: `router/index.ts`、`App.vue`、读者分析沉默读者名单和增长闭环博主告警的姓名链接
- [ ] `npm run build` 通过，提交。

### Task 8: 真实环境验证与文档
- [ ] 全量测试、重建全栈、`seed-demo.sh`、`smoke-test.sh`（加创作者/用户检查）、浏览器走查。
- [ ] 设计文档改为"已实现"并写偏差；主设计文档、README、记忆更新；提交。
