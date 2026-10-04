# 积分排行榜（月榜/年榜）· 设计方案

- 日期：2026-10-04
- 状态：已实现（实施计划见 [积分排行榜](../plans/2026-10-04-leaderboard.md)）
- 前置依赖：[基础设施与数据模型](2026-09-23-community-analytics-design.md)（Plan 1，`points_ledger` 是积分的唯一事实来源）、[积分引擎 + Kafka 实时流水线](2026-09-23-points-engine-kafka-pipeline-design.md)（Plan 2，实时事件也会写 `points_ledger`）、[PV 多维度聚合](2026-10-04-pv-analytics-design.md)（Plan 3，复用其中的 `Clock` Bean 和 `date_trunc`/Timestamp 的约定）。

## 1. 背景与目标

母设计文档要求月度/年度"最热博主"排行榜按**当期有效积分**排名，而不是历史累计。本阶段交付榜单后端：当期榜单实时计算、已结束周期的榜单快照永久归档、一组查询和管理接口。

## 2. 范围

**包含**：
- 快照表 `leaderboard_snapshot` 及其重算服务（定时 + 管理员手动触发）
- 月榜/年榜查询接口（进行中实时算，已结束读快照，快照缺失时回退实时算）

**明确不包含**（见第 9 节）：Redis 实时榜、Kafka 消费者改动、前端、TOP1000 KOL 名单、个人排名历史接口、鉴权。

**与母设计文档的两处差异**：
1. 不做 Redis Sorted Set 实时榜（母文档 3.5、5 节）。当期榜单在查询时直接对 `points_ledger` 区间求和，数据永远准确；和 Plan 3 对 PV 的取舍一致。
2. 不建 `points_summary_monthly/yearly`（母文档 3.4）。查询时直接求和已经够用，主动/被动拆分直接放在快照表里。

## 3. 口径

- **榜单分数** = 周期内 `points_ledger.points` 之和（主动 + 被动），同时返回主动分和被动分。
- **周期**：`MONTH`（`yyyy-MM`，如 `2026-09`）、`YEAR`（`yyyy`，如 `2026`）。区间是左闭右开 `[周期起点 00:00:00, 下一周期起点 00:00:00)`，按 `occur_time` 判断。
- **排名**：积分降序；并列同名次（1,2,2,4）；并列时按员工 id 升序排列显示；当期没有任何流水的员工不上榜（权重都是正数，不会出现负分，不做特殊处理）。
- **"只算当期"**：这是需求核心——1 月积分很高、2 月没有流水的员工，不会出现在 2 月榜上。

## 4. 数据模型

追加到 `backend/src/main/resources/schema.sql`：

```sql
CREATE TABLE IF NOT EXISTS leaderboard_snapshot (
    period_type    VARCHAR(10) NOT NULL CHECK (period_type IN ('MONTH', 'YEAR')),
    period_value   VARCHAR(10) NOT NULL,
    rank_no        INT NOT NULL,
    employee_id    BIGINT NOT NULL REFERENCES employee(id) ON DELETE CASCADE,
    points         BIGINT NOT NULL,
    active_points  BIGINT NOT NULL,
    passive_points BIGINT NOT NULL,
    PRIMARY KEY (period_type, period_value, employee_id)
);

CREATE INDEX IF NOT EXISTS idx_leaderboard_snapshot_rank
    ON leaderboard_snapshot(period_type, period_value, rank_no);
```

- 列名用 `rank_no`，因为 `rank` 是 SQL 保留字。
- `ON DELETE CASCADE` 的原因同 Plan 3：回填会 `deleteAll()` 全部员工，快照是派生数据，随员工级联删除，回填逻辑不用改。
- 不建 JPA 实体，全部用 `JdbcTemplate`。`period_value` 用字符串，所以不涉及 A 兼容模式的 DATE 问题；`occur_time` 是真正的 `TIMESTAMP`，参数一律绑定 `Timestamp`。

## 5. 快照生成

`LeaderboardSnapshotService`：

- `rebuild(PeriodType type, String periodValue)`：一个事务内先删该期旧快照，再 `INSERT … SELECT employee_id, RANK() OVER (ORDER BY SUM(points) DESC), SUM(points), SUM(CASE WHEN category='ACTIVE' …), SUM(CASE WHEN category='PASSIVE' …) FROM points_ledger WHERE occur_time >= ? AND occur_time < ? GROUP BY employee_id`。返回写入行数。
  - 只允许对**已结束**的周期（周期终点早于今天，即下一周期起点 ≤ 今天）重算；进行中或未来的周期抛 `InvalidLeaderboardQueryException`（400）。
- `rebuildAllEnded()`：对"已结束且 `points_ledger` 里有流水"的每个月和每个年重算，返回 `{months, years, rowsWritten}`。首次回填完历史数据后调用一次即可补齐快照。
- 触发方式：
  - **定时**：`LeaderboardSnapshotScheduler`，`@Scheduled(cron = "${analytics.leaderboard.snapshot-cron:0 20 0 * * *}")`，每天重算"上一个月"和"上一年"。幂等，也能自愈漏跑；受 `analytics.leaderboard.schedule.enabled`（默认 true，测试里 false）控制。
  - **手动**：`POST /api/admin/analytics/leaderboard/snapshot`，不带参数 = `rebuildAllEnded()`；带 `periodType` 和 `periodValue` = 只重算那一期。

## 6. 查询接口

`GET /api/leaderboard/{month|year}?period=2026-09&limit=50`

- `period` 可省略，默认当前周期；`limit` 默认 50，范围 1~100（以校验过的整数字面量写进 SQL，OpenGauss 不接受绑定参数分页）。
- 响应：

```json
{
  "periodType": "MONTH",
  "periodValue": "2026-09",
  "status": "IN_PROGRESS",
  "source": "LIVE",
  "entries": [
    {"rank": 1, "employeeId": 3601, "name": "员工-1", "points": 300, "activePoints": 300, "passivePoints": 0}
  ]
}
```

- **数据来源**：
  - 进行中的周期（含当前周期）→ 实时求和，`status=IN_PROGRESS`，`source=LIVE`。
  - 已结束的周期 → 有快照读快照（`status=ARCHIVED`，`source=SNAPSHOT`）；没有快照回退实时求和（`status=ARCHIVED`，`source=LIVE`），数据一致。判断"有没有快照"= 该期在 `leaderboard_snapshot` 里是否存在任何一行。
- 路径变量只接受小写 `month`/`year`。
- 错误（HTTP 400，响应体是一句文字）：路径类型非法、`period` 格式错误、未来周期、`limit` 越界。没有任何数据的周期不是错误，返回空列表。

## 7. 组件划分

包名 `com.jiaxian.analytics.leaderboard`：

| 组件 | 职责 |
|---|---|
| `PeriodType` | 纯逻辑：`MONTH`/`YEAR` 的起点、下一周期起点、格式化、解析 |
| `LeaderboardEntry`、`Leaderboard` | 响应记录（`Leaderboard` 含 `status`、`source` 枚举） |
| `InvalidLeaderboardQueryException` | 参数非法 → 400 |
| `LeaderboardQueryRepository` | 实时榜（对流水求和 + 排名）、快照榜、"快照是否存在"、"有流水的周期列表" |
| `LeaderboardSnapshotService` | 重算快照 |
| `LeaderboardSnapshotScheduler` | 定时重算上一个月/上一年 |
| `LeaderboardService` | 校验参数、选择数据来源，持有 `Clock`（复用 Plan 3 的 `PvConfig.clock()` Bean） |
| `LeaderboardController`、`LeaderboardAdminController`、`LeaderboardExceptionHandler` | HTTP 接口与 400 处理 |

实时榜的并列排名用与快照相同的 `RANK() OVER` SQL，保证两条路径的名次一致。

## 8. 测试策略

- **纯逻辑单测**：`PeriodType`（月/年起点和下一周期、跨年、2 月、解析和格式化、非法格式）。
- **对真实 OpenGauss 的集成测试**，数字全部手算：
  - 区间边界：周期起点 00:00:00 算本期，上一周期 23:59:59 不算。
  - 只算当期：1 月高、2 月无流水的员工，不在 2 月榜上。
  - 主动/被动拆分；并列名次 1,2,2,4；0 分的人不上榜；`limit` 截断。
  - 快照重算幂等、先删后插、拒绝进行中和未来的周期；`rebuildAllEnded` 只处理已结束且有流水的周期。
  - 员工删除时快照级联删除。
- **服务层单测**：固定 `Clock`，覆盖进行中 / 已归档有快照 / 已归档无快照 / 未来周期 / 参数校验。
- **接口测试**：400 响应、响应结构、管理接口。
- **端到端**：历史回填 → 手动重算全部快照 → 每个已结束周期的快照积分总和 = 同区间 `points_ledger` 总和；快照榜与实时榜（回退路径）逐行一致；再次回填后快照随员工级联清空。

## 9. 明确不做的范围（Out of Scope）

- Redis 实时榜、Kafka 消费者和事件处理的任何改动。
- 前端页面。
- 滚动近 12 个月的 TOP1000 名单（属于 KOL/RFM 那份计划）。
- 个人历史排名轨迹接口（属于博主详情页那份计划；快照表已经够它使用）。
- 接口鉴权（沿用项目其他管理接口的做法）。
