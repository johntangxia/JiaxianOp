# 行为数据扩充 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans（本计划由同一会话内联执行）。步骤用复选框跟踪。

**Goal:** 给浏览记录加上来源、入口、停留，新增曝光、关注、搜索、标签四张表，并让回填和实时模拟都产生这些数据。

**Architecture:** 纯函数式的抽样器（`SourceSampler`、`ViewDetailSampler`、`TagCatalog`、`ImpressionPlanner`）放在 `simulator` 包，回填服务和实时规划器共用；`BehaviorEventGenerator.generateView` 接收 `ViewDetail` 并同时落点击的曝光；新表走 `schema.sql`，旧表加列走 `SchemaUpgrader`。设计见 [行为数据设计](../specs/2026-10-05-behavior-data-design.md)。

**Tech Stack:** Spring Boot 3.2 / JPA + JdbcTemplate / OpenGauss 5.0（A 兼容）/ Kafka / JUnit 5 + AssertJ。

**必读：沿用的约定与踩坑**：`DATE`/`TIMESTAMP` 绑定 `Timestamp`；不支持 `ADD COLUMN IF NOT EXISTS`（用 `SchemaUpgrader`）；跑后端测试前先 `docker stop jiaxian-backend`（共用 Kafka 消费组）；每个任务后跑完整 `mvn test`（后台运行，约 6 分钟）。

---

### Task 1: 表结构与实体
- Modify: `schema.sql`（`impression`、`follow_relation`、`search_log`、`content_tag`，`pv_log` 新列）、`SchemaUpgrader.java`（`pv_log` 四列）、`domain/PvLog.java`
- Create: `domain/ViewSource.java`、`domain/Impression.java`、`domain/FollowRelation.java`、`domain/SearchLog.java`、`domain/ContentTag.java` 及对应 `repository/*Repository.java`
- Test: 扩展 `SchemaUpgraderIT`；新增 `repository/BehaviorTablesIT.java`（四张表各存取一条，外键和唯一约束生效）
- [ ] 实现并通过测试，提交。

### Task 2: 抽样器（纯函数）
- Create: `simulator/SourceSampler.java`、`ViewDetailSampler.java`、`TagCatalog.java`、`ImpressionPlanner.java`（常量集中在各自类里）
- Test: 对应的 `*Test`
- [ ] 先写测试（占比容差、退回规则、停留范围、读完比例、标签数量和幂律、期望曝光数），再实现，提交。

### Task 3: 浏览详情与曝光落库
- Modify: `simulator/BehaviorEventGenerator.java`（`generateView` 带 `ViewDetail`、写点击的曝光；新增 `generateImpression`、`generateFollow`、`generateSearch`、`generateTags`）、`ViewerPicker.java`（限定候选集合的重载）
- Test: `BehaviorEventGeneratorTest`（Mockito）
- [ ] 实现并通过，提交。

### Task 4: 回填
- Modify: `simulator/HistoricalBackfillService.java`（先生成关注关系和标签；浏览按来源挑人；曝光、搜索；清理新表）
- Test: `simulator/HistoricalBackfillBehaviorIT.java`；调整受影响的既有 IT
- [ ] 实现并通过，提交。

### Task 5: 实时模拟
- Modify: `RealtimeEvent.java`、`RealtimeEventType.java`、`RealtimeEventPlanner.java`、`RealtimeEventHandler.java`、`RealtimeCounterUpdater.java` 及对应测试
- [ ] 实现并通过，提交。

### Task 6: 真实环境验证与文档
- [ ] 全量 `mvn test`、重建全栈、`seed-demo.sh`、`smoke-test.sh`。
- [ ] `gsql` 抽查：来源占比、各入口 CTR、自主入口占比、关注/搜索/标签行数、停留分布。
- [ ] 设计文档改为"已实现"并写偏差；主设计文档加一句取舍；README 更新；提交。
