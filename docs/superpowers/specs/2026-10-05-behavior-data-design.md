# 行为数据扩充（来源、曝光、停留、关注、搜索、标签）· 设计方案

- 日期：2026-10-05
- 状态：已实现（实施计划见 [行为数据扩充](../plans/2026-10-05-behavior-data.md)）
- 前置依赖：[数据基础](2026-10-05-data-foundation-design.md)（5 个综合频道 + 23 个领域频道、加入社区日期、`ActivityModel`/`ViewerPicker`）已实现。

## 1. 背景与目标

后面的频道驾驶舱、竞赛榜、读者侧分析都要回答"流量从哪来、各个入口分发得好不好"。现在的浏览记录只有"谁在什么时候看了哪篇内容"。本计划补齐：

1. 每次浏览的**来源**，以及领域频道**自主入口**与综合频道入口的区分。
2. 各入口的**曝光**和点击，用来算点击率（CTR）。
3. 浏览的**停留时长**、是否读完/播完。
4. **关注关系**（只记录，不计分）和"关注"入口的分发约束。
5. **搜索日志**和内容**标签**。

不做：任何新的分析接口和页面（计划 12 起）、关注计分。

## 2. 数据模型

| 表 | 说明 |
|---|---|
| `pv_log`（加列） | `entry_channel_id`（FK `channel`，ENTRY 才有）、`source_type`、`dwell_seconds`、`completed`。旧数据四列为空，现有查询不受影响 |
| `impression`（新） | `viewer_employee_id`、`content_id`、`entry_channel_id`（NOT NULL）、`impression_time`、`clicked`。点击的曝光对应一条 `pv_log` |
| `follow_relation`（新） | `follower_employee_id`、`account_id`、`follow_time`、`unfollow_time`（空表示仍在关注）。只记录，不计分 |
| `search_log`（新） | `employee_id`、`keyword`、`search_time`、`clicked_content_id`（可空） |
| `content_tag`（新） | `content_id`、`tag`，同一内容的标签唯一 |

- 旧库升级：`pv_log` 的四个新列走 `SchemaUpgrader.COLUMNS`；新表用 `CREATE TABLE IF NOT EXISTS`。
- `source_type` 取值（枚举 `ViewSource`）：`ENTRY` 用户从主页的频道入口点进来；`SEARCH` 搜索；`SHARE` 转发链接；`OTHER` 其他未归因的来源（消息推送、直接打开链接等）。
- **自主入口 / 综合入口不单独存字段**，由 `entry_channel_id` 推导：等于内容所属领域频道 → 自主入口；是 5 个综合频道之一 → 综合入口。

## 3. 来源模拟（参考值来自业务方的现状）

每次浏览的来源按下表抽样（占全部 PV 的比例）：

| 来源 | 占比 | 说明 |
|---|---|---|
| SHARE | 40% | 不经过任何入口，不要求与转发互动记录一一对应 |
| ENTRY 综合入口 | 15% | 推荐 8%、热点 3%、视频 2%（只分发视频类内容）、关注 2%（只分发浏览者已关注账号的内容） |
| SEARCH | 10% | |
| ENTRY 自主入口 | 2% | 入口频道 = 内容所属领域频道 |
| OTHER | 33% | 业务方给出的四项合计 67%，余下的归入 OTHER，四项数值保持不变 |

- 先抽来源，再挑浏览者：关注入口从"当时正在关注该内容账号"的员工里挑；挑不出（没有关注者）退回推荐；视频入口遇到文章退回推荐。其他来源沿用 `ViewerPicker`。
- **曝光与点击率**：只有 ENTRY 来源有曝光。每个点击对应一条已点击的曝光，另外按 `(1 − CTR) / CTR` 的期望（随机取整）生成同一内容、同一入口、由其他员工看到但没点击的曝光。点击率常量：关注 0.20、自主入口 0.12、视频 0.10、热点 0.06、推荐 0.04（经验值，可调整）。未点击的曝光也落库。
- **停留时长**：文章中位数约 60 秒、视频约 80 秒，对数正态，限制在合理范围内；文章读完概率 0.55，视频播完概率 0.35，读完/播完的停留放大 1.6 倍。
- **关注关系**：回填在生成浏览之前先生成。员工关注 `round(2 + 10 × 活跃倾向)` 个账号，70% 偏向与自己同领域的账号；关注时间在加入社区之后；约 12% 的关注在 30–200 天后取消。
- **搜索**：每个 SEARCH 浏览对应一条有点击的搜索日志，另外约 0.4 条无点击的搜索。关键词：60% 取内容的标签、25% 取内容所属领域名、15% 取词表里的任意标签。
- **标签**：每篇内容 1 个领域名标签 + 2–3 个来自共享词表（按幂律挑选）的标签。

## 4. 实时模拟

- `RealtimeEvent` 增加 `sourceType`、`entryChannelId`、`dwellSeconds`、`completed`；`RealtimeEventType` 增加 `IMPRESSION`。
- `RealtimeEventPlanner` 的 VIEW 事件用同一套来源抽样（关注入口因为拿不到关注关系，退回推荐）；每个 ENTRY 浏览后额外发出若干未点击的 `IMPRESSION` 事件（不计入 `eventCount`）。
- `RealtimeEventHandler` 落库浏览详情和曝光；`RealtimeCounterUpdater` 忽略曝光。
- 实时侧不生成关注、搜索、标签。

## 5. 测试

- 纯函数：`SourceSampler`（占比、退回规则）、`ViewDetailSampler`（停留范围、读完比例）、`TagCatalog`（标签数、幂律）、`ImpressionPlanner`（期望曝光数）。
- 集成：`SchemaUpgraderIT`（旧库补列）；`HistoricalBackfillBehaviorIT`（来源占比在容差内、关注入口的浏览者当时确实在关注、自主入口频道等于内容所属领域、点击的曝光数等于 ENTRY 的浏览数、各入口 CTR 的顺序、关注/搜索/曝光不早于加入日期、每篇内容有标签、搜索日志有点击和无点击两类）。
- 既有测试：`RealtimeEventPlannerTest`、`RealtimeEventHandlerTest`、`RealtimePipelineIT` 随事件结构调整；其余必须继续全部通过。

## 5.1 实现时的偏差

- **库结构要在 Hibernate 之前建好**：`defer-datasource-initialization=true` 让 Spring Boot 自己的 `schema.sql` 在 Hibernate `validate` 之后才执行，新增带实体的表会报"缺表"（之前所有带实体的表都早于实体存在，没暴露；新库启动实际上也会失败）。`SchemaUpgrader` 现在先补列，再在 Hibernate 之前执行一遍幂等的 `schema.sql`，新库、旧库都能启动，已用空 schema 实测。
- `pv_log` 之外的新增行为（关注、搜索、标签）只在回填里生成；`BehaviorEventGenerator` 保留了 8 参数构造函数（不记录曝光等），12 参数的是完整版。
- 关注规划抽成 `FollowPlanner`，没有放进回填服务。
- 实时侧的 `RealtimeEventPlanner.planBatch` 多了一个带综合频道 id 的重载；不传时行为与以前完全一致。`RealtimeEventProducer` 因此多依赖了 `ChannelRepository`。
- **KOL 统计日**：新的浏览热度衰减让 PV 的最后一天只比历史结束日晚几天，原来的"PV 末日 − 30 天"会把统计日推得太早（新人还没加入、RISING 分层消失）。`POST /api/admin/analytics/kol/rebuild` 不传 `asOf` 时改为取最近一次登录/发帖的日期（不晚于今天），前端一键重算和 `seed-demo.sh` 都不再自己估算，前端删掉了 `kolAsOfFromPvEnd`。
- **RISING 画像**的最近一次登录从 `end−4` 改成 `end−1`：模拟里有大量几乎每天登录的常驻员工，4 天前的新近度排名只是中游，落不进"成长潜力新人"（`KolEndToEndIT` 因此失败，已修正）。
- 验收数据（演示库，2260 次浏览）：分享 42%、其他 32%、搜索 10%、入口 17%（其中自主入口约 2.4%）；各入口点击率与设定一致（推荐 4%、热点 6%、视频 10%、自主 12%、关注 20%）。

## 6. 明确不做的范围（Out of Scope）

- 分析接口、页面、驾驶舱（计划 12 起）。
- 关注计分；关注/搜索/标签的实时模拟。
- 分享来源与转发互动记录的一一对应；转发者归因。
- 自由文本搜索、搜索联想。
- 入口位置、置顶位、不感兴趣（已确定不做）。
