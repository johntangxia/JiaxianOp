# 读者侧分析 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans（本计划由同一会话内联执行）。步骤用复选框跟踪。

**状态：已全部执行**（偏差见设计文档 6.1 节，含回填提速和 `/api/readers/range`）。

**Goal:** 从读者一侧给出 DAU/WAU/MAU、新成员留存同期群、激活漏斗、读者分层和沉默读者名单。

**Architecture:** 后端新增 `reader` 包（`ReaderQueryRepository` 取 (员工, 日) 去重的活跃行和各类首次行为，`ReaderService` 在 Java 里按周期归并并装配同期群、漏斗、分层，`ReaderController` 提供五个接口）；前端新增 `/readers` 页面四个标签页。设计见 [读者侧分析设计](../specs/2026-10-05-reader-analytics-design.md)。

**Tech Stack:** Spring Boot 3.2 / JdbcTemplate / Vue 3 + TypeScript + Element Plus + ECharts / JUnit 5 + Vitest。

**必读：沿用的约定与踩坑**：区间半开、日期绑 `Timestamp`；`LIMIT/OFFSET` 必须是校验过的整数字面量；`@Repository` 会包装 `IllegalArgumentException`，参数校验放服务；跑后端测试前先 `docker stop jiaxian-backend`，测完重跑 `seed-demo.sh`；`el-table` 插槽 `row` 要断言。

---

### Task 1: 纯函数（周期归并、同期群、漏斗、角色）
- Create: `reader/ActivityAggregator.java`、`CohortBuilder.java`、`FunnelBuilder.java`、`ReaderRole.java` 及 record
- Test: 对应的 `*Test`
- [x] 先写测试再实现，提交。

### Task 2: 查询仓库
- Create: `reader/ReaderQueryRepository.java`
- Test: `ReaderQueryRepositoryIT`（2019 夹具）
- [x] 实现并通过，提交。

### Task 3: 服务、接口与异常处理
- Create: `ReaderService`、`ReaderController`、`ReaderExceptionHandler`、`InvalidReaderQueryException`
- Test: `ReaderServiceTest`、`ReaderControllerIT`、`ReaderEndToEndIT`
- [x] 通过后跑完整 `mvn test`（后台），提交。

### Task 4: 前端数据层
- Create: `api/readers.ts`、`api/types.ts` 增补、`utils/readers.ts`、`charts/*`（活跃趋势、漏斗、分层占比、热力色）及测试
- [x] `npx vitest run` 通过，提交。

### Task 5: 前端页面与默认值
- Create: `views/readers/ReadersView.vue` 及四个标签组件；Modify: `router/index.ts`、`App.vue`、`utils/simulatorForms.ts`（默认员工数 200）、`scripts/seed-demo.sh`
- [x] `npm run build` 通过，提交。

### Task 6: 真实环境验证与文档
- [x] 全量测试、重建全栈、`seed-demo.sh`（200 人）、`smoke-test.sh`（加读者分析检查）、浏览器走查。
- [x] 设计文档改为"已实现"并写偏差；主设计文档、README、记忆更新；提交。
