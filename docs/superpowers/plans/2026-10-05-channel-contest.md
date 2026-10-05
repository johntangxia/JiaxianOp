# 频道竞赛榜 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans（本计划由同一会话内联执行）。步骤用复选框跟踪。

**状态：已全部执行**（偏差见设计文档 6.1 节）。

**Goal:** 领域榜和综合榜分开，用榜内百分位加权的健康分给频道排名，带名次变化和进步榜。

**Architecture:** 后端新增 `contest` 包：纯函数 `ContestScorer` 打分，`ContestService` 复用 `ChannelCockpitService` 的频道指标（本期和上一个自然周期各取一次）装配榜单，`ContestController` 提供两个接口；前端新增 `/contest` 页面。设计见 [竞赛榜设计](../specs/2026-10-05-channel-contest-design.md)。

**Tech Stack:** Spring Boot 3.2 / JdbcTemplate / Vue 3 + TypeScript + Element Plus / JUnit 5 + Vitest。

**必读：沿用的约定与踩坑**：区间半开、日期绑 `Timestamp`；`@Repository` 会包装 `IllegalArgumentException`，参数校验放服务；跑后端测试前先 `docker stop jiaxian-backend`；每个任务后跑完整测试（后端在后台跑）。

---

### Task 1: 打分纯函数
- Create: `contest/ContestScorer.java`、`DimensionDef.java`、`Candidate.java`、`ScoredChannel.java`、`DimensionScore.java`
- Test: `ContestScorerTest`
- [x] 先写测试再实现，提交。

### Task 2: 服务与周期
- Modify: `channel/ChannelCockpitService.java`（公开 `snapshot(from, to)`：每个频道的 `ChannelPeriod`，不含诊断）
- Create: `contest/ContestType`、`PeriodOption`、`ContestEntry`、`ContestBoards`、`ContestRepository`（PV 数据范围）、`ContestService`、`InvalidContestQueryException`
- Test: `ContestServiceTest`
- [x] 实现并通过，提交。

### Task 3: 接口与异常处理
- Create: `ContestController`、`ContestExceptionHandler`
- Test: `ContestControllerIT`、`ContestEndToEndIT`
- [x] 通过后跑完整 `mvn test`（后台），提交。

### Task 4: 前端数据层
- Create: `api/contest.ts`、`api/types.ts` 增补、`utils/contest.ts` 及测试
- [x] `npx vitest run` 通过，提交。

### Task 5: 前端页面
- Create: `views/contest/ContestView.vue`、`BoardTable.vue`、`PodiumCards.vue`、`ImproversPanel.vue`；Modify: `router/index.ts`、`App.vue`
- [x] `npm run build` 通过，提交。

### Task 6: 真实环境验证与文档
- [x] 全量测试、重建全栈、`seed-demo.sh`、`smoke-test.sh`（加竞赛榜检查）、浏览器走查。
- [x] 设计文档改为"已实现"并写偏差；主设计文档、README、记忆更新；提交。
