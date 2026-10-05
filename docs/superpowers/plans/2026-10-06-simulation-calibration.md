# 模拟数据校准 Implementation Plan

**状态：已全部执行**（实测与偏差见设计文档 7.1 节；后端 678 个测试、前端 151 个测试通过，冒烟测试通过）。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans（本计划由同一会话内联执行）。步骤用复选框跟踪。

**Goal:** 让演示数据的量级、结构和时间规律可信：浏览由登录产生、画像比例缩小、留存和关注做成长尾，并满足设计文档第 2 节的验收表。

**Architecture:** 纯函数（`ViewPlanner`、画像/登录/淡出/关注/账号幂律）放在 `simulator` 包；`HistoricalBackfillService` 改为"先规划登录 → 按登录生成浏览 → 生成互动"；浏览相关的行（pv_log、曝光、VIEW 明细、搜索、积分）用新的 `ViewBatchWriter` 以 JDBC 批量写入。设计见 [模拟数据校准设计](../specs/2026-10-06-simulation-calibration-design.md)。

**Tech Stack:** Spring Boot 3.2 / JPA + JdbcTemplate / OpenGauss 5.0 / JUnit 5 + AssertJ + Mockito。

**必读：沿用的约定与踩坑**：跑后端测试前先 `docker stop jiaxian-backend`；完整 `mvn test` 后台运行；回填类 IT 会清空演示数据，测完要重新 `./scripts/seed-demo.sh`；测试里写的断言要保留"验证的业务含义"，只改数字。

---

### Task 1: 画像、登录、留存（纯函数）
- Modify: `PersonaAssigner`（3/2/3/5%，每类至少 2 人）、`PersonaLoginPlanner`（RISING 新规划需要加入日；STAR/CHURNING 乘周末因子）、`EmployeeProfile`（加 `returnDay`）、`ActivityModel`（回流、RISING 概率）、`EntityGenerator`（RISING 加入日 21–60 天、老成员 70%、分型 30/50/20、淡出起点分散、回流）
- Test: 调整 `PersonaAssignerTest`、`PersonaLoginPlannerTest`、`ActivityModelTest`、`EntityGeneratorTest`
- [x] 实现并通过，提交。

### Task 2: 关注与账号幂律、作者先读后写
- Modify: `FollowPlanner`（`4 + 30×倾向^0.6`、账号权重）、`EntityGenerator`（账号幂律权重、发布时间 ≥ 作者加入 + 7 天、`GeneratedEntities` 带账号权重）
- Test: `FollowPlannerTest`（人均关注、头部集中）、`EntityGeneratorTest`
- [x] 实现并通过，提交。

### Task 3: `ViewPlanner`（纯函数）
- Create: `ViewPlanner`（Poisson 次数、倍率、内容候选窗口与权重、来源约束、关注/视频/自主入口）
- Test: `ViewPlannerTest`
- [x] 先写测试再实现，提交。

### Task 4: `ViewBatchWriter` 与回填重构
- Create: `ViewBatchWriter`；Modify: `SimulationConfig`（`viewsPerActiveDay`）、`SimulatorController`、`HistoricalBackfillService`（登录先于浏览、互动概率、曝光观看者取自当天登录者）
- Test: `ViewBatchWriterIT`（逐行与 `generateView` 一致）
- [x] 实现并通过，提交。

### Task 5: 集成测试调整与 `CalibrationIT`
- Modify: 受影响的既有 IT；Create: `CalibrationIT`
- [x] 跑完整 `mvn test`（后台），调整断言并如实记录，提交。

### Task 6: 前端小改动
- Modify: 读者留存、创作者留存同期群人数 < 5 置灰并提示
- [x] `npx vitest run`、`npm run build` 通过，提交。

### Task 7: 真实环境验证
- [x] 重建全栈、`seed-demo.sh`，用 SQL 逐项实测验收表，量回填耗时和接口耗时，`smoke-test.sh`，KOL 分布决定 `top-n`，浏览器走查。

### Task 8: 文档
- [x] 设计文档改为"已实现"并写偏差；README 已知限制；主设计文档；记忆；提交。
