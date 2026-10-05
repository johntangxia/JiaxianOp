# 数据基础（频道体系与模拟真实度）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans（本计划由同一会话内联执行）。步骤用复选框跟踪。

**Goal:** 把频道换成 5 综合 + 23 领域，员工加上"加入社区日期"，并让历史回填和实时模拟带上留存分型、周末/时段规律、热度衰减、同领域偏好、沉默读者。

**Architecture:** 种子数据与旧库升级走 `data.sql` 和新的 `SchemaUpgrader`；行为规则集中在纯函数式的 `ActivityModel` 和 `EmployeeProfile`（`simulator` 包），`EntityGenerator` 负责生成员工/内容，`HistoricalBackfillService` 负责行为事件。设计见 [数据基础设计](../specs/2026-10-05-data-foundation-design.md)。

**Tech Stack:** Spring Boot 3.2 / JdbcTemplate + JPA / OpenGauss 5.0（A 兼容）/ JUnit 5 + AssertJ / Vue 3 + Vitest。

**必读：沿用的约定与踩坑**见各计划的"约定与踩坑记录"，并记住本计划用到的几条：`DATE` 列是 `timestamp`（实体字段加 `@JdbcTypeCode(SqlTypes.TIMESTAMP)`）；不支持 `ADD COLUMN IF NOT EXISTS`；`*IT` 连真实库；每个任务后跑完整 `mvn test`。

---

### Task 1: 频道种子与旧库升级
- Modify: `backend/src/main/resources/data.sql`（28 个频道 + 旧库迁移语句）
- Modify: `domain/Channel.java`（常量）、`repository/ChannelRepository.java`（`findByCategory`、`deleteByCategory`）
- Test: `backend/src/test/java/com/jiaxian/analytics/repository/ChannelSeedIT.java`
- [ ] 写 `ChannelSeedIT`：总数 ≥ 28；`综合频道` 恰好是关注/推荐/热点/视频/直播；`领域频道` 恰好 23 个并包含"自动驾驶网络ADN"和"2012"；不存在 `专业领域` 类别。
- [ ] 改 `data.sql`、`Channel`、`ChannelRepository`；跑测试直到通过。
- [ ] 提交。

### Task 2: `SchemaUpgrader` 与 `community_join_date`
- Create: `backend/src/main/java/com/jiaxian/analytics/SchemaUpgrader.java`
- Modify: `schema.sql`（employee 表加列）、`domain/Employee.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/SchemaUpgraderIT.java`
- [ ] 写 `SchemaUpgraderIT`：先 `ALTER TABLE employee DROP COLUMN community_join_date`，调用 `upgrader.afterPropertiesSet()` 后列存在；再调用一次不抛异常。
- [ ] 实现升级器 + `EntityManagerFactoryDependsOnPostProcessor`，`schema.sql` 加列，实体加字段。
- [ ] 在已有数据的真实库上启动后端，确认 `validate` 通过。
- [ ] 提交。

### Task 3: 员工画像与活跃模型
- Create: `simulator/RetentionArchetype.java`、`simulator/EmployeeProfile.java`、`simulator/ActivityModel.java`
- Test: `simulator/ActivityModelTest.java`
- [ ] 写测试：加入日之前概率为 0；常驻 > 偶尔；淡出型衰减后为 0；周末 = 工作日 × 0.35；画像员工的概率值；小时采样的高峰占比；衰减采样的中位数约等于半衰期、截断在 30 天内；相同种子结果相同。
- [ ] 实现并通过。
- [ ] 提交。

### Task 4: `EntityGenerator` 改造
- Modify: `simulator/EntityGenerator.java`（部门、加入日期、画像、作者先于发布时间、70% 同领域频道）
- Modify: `simulator/EntityGeneratorTest.java`
- [ ] 测试：`dept` 全部来自领域频道名；约 45% 老成员；RISING 员工的加入日在历史结束前 21–25 天；内容发布时间不早于作者加入日；内容频道全部是领域频道；没有领域频道时抛 `IllegalStateException`。
- [ ] 实现并通过。
- [ ] 提交。

### Task 5: 回填改造
- Modify: `simulator/HistoricalBackfillService.java`（登录、浏览、互动、清理 `已停用` 频道）
- Modify: `simulator/HistoricalBackfillPersonaIT.java`；Create: `simulator/HistoricalBackfillRealismIT.java`
- [ ] `HistoricalBackfillRealismIT` 断言：加入日之前没有任何事件（登录、浏览、点赞、评论、转发、收藏、发帖）；沉默读者没有任何互动，也没有评论；同领域浏览占比 ≥ 55%；新成员占比 25–65%；浏览数在发布后 30 天内；周末浏览日均低于工作日。
- [ ] 改造并通过，修正受影响的既有 IT（若有）。
- [ ] 提交。

### Task 6: 实时模拟尊重加入日期
- Modify: `realtime/RealtimeEventPlanner.java`、`realtime/RealtimeEventPlannerTest.java`
- [ ] 测试：未加入的员工从不作为演员；`communityJoinDate` 为空视为已加入；全部未加入时返回空列表。
- [ ] 实现并通过。
- [ ] 提交。

### Task 7: 前端、脚本与文档
- Modify: `frontend/src/views/OverviewView.vue`（只列领域频道）及其测试；`scripts/smoke-test.sh`；`backend/.../DimensionControllerIT`、`DimensionOptionsRepositoryIT`（频道数 ≥ 28）；`README.md`
- [ ] 前端过滤并补测试；脚本和测试中的 15 改为 28；README 更新。
- [ ] `mvn test` 与 `npm test` 全过。
- [ ] 提交。

### Task 8: 真实环境验证
- [ ] 重建并启动全栈（`DOCKER_BUILDKIT=0 COMPOSE_BAKE=false docker compose --profile app up -d --build`），确认旧库启动没有 `validate` 报错、旧频道被迁移。
- [ ] 跑 `scripts/seed-demo.sh` 和 `scripts/smoke-test.sh`。
- [ ] 用 `gsql` 抽查：频道数、员工部门、加入日期分布、沉默读者比例、周末与工作日浏览差。
- [ ] 浏览器检查总览页的频道筛选只有 23 个领域频道。
- [ ] 把设计文档状态改为"已实现"，在主设计文档加一句实现取舍，提交。
