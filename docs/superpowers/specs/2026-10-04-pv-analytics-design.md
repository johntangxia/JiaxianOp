# PV 多维度聚合与预测 · 设计方案

- 日期：2026-10-04
- 状态：已通过讨论确认，待转入实施计划
- 前置依赖：[基础设施与数据模型](2026-09-23-community-analytics-design.md)（Plan 1）和 [积分引擎 + Kafka 实时流水线](2026-09-23-points-engine-kafka-pipeline-design.md)（Plan 2）已完成。本设计建立在它们已经写入 `pv_log`（历史回填和实时事件两条路径都会写）的数据之上。

## 1. 背景与目标

母设计文档第 5 节要求：从频道、账号、博主、内容类型等维度对内容浏览量（PV）做长期监控，支持同比、环比和简单的趋势预测。本阶段交付这套分析后端：一张按天预聚合的汇总表、一组多维度的查询接口，以及不依赖数据库的同环比和预测计算逻辑。

核心目标：
1. 用一张最细粒度的汇总表支撑任意维度和任意交叉筛选，避免为每种维度组合单独建表。
2. 把分桶、补零、同比环比、预测写成纯 Java 逻辑，能用手算的具体数字做单元测试。
3. 汇总数据可以随时重算（幂等），并且每天自动把最近几天补进来（实时事件也在其中）。

## 2. 范围

**包含**：
- 汇总表 `pv_summary_daily` 及其重算服务（定时 + 管理员手动触发）
- 趋势接口（带同比、环比、预测）
- Top N 排行接口（频道/账号/博主/内容类型）

**明确不包含**（见第 10 节）：前端页面、消费 Kafka 事件实时更新汇总表、积分排行榜、KOL/RFM、更复杂的预测模型。

## 3. 数据模型

新增一张表，追加到 `backend/src/main/resources/schema.sql`：

```sql
CREATE TABLE IF NOT EXISTS pv_summary_daily (
    content_id   BIGINT NOT NULL REFERENCES content(id) ON DELETE CASCADE,
    summary_date DATE NOT NULL,
    pv_count     BIGINT NOT NULL,
    PRIMARY KEY (content_id, summary_date)
);
```

`ON DELETE CASCADE` 是必需的：汇总表是派生数据，而 Plan 1 的 `HistoricalBackfillService.clearPreviousSimulationData()` 每次重跑都会 `deleteAll()` 全部内容；没有级联的话，一旦汇总表里有数据，再次回填就会撞外键约束。有了级联，内容被删时它的汇总行自动消失，回填逻辑不用改。

- 粒度是"内容 × 天"，行数约等于内容数 × 活跃天数，规模很小。频道、账号、博主、内容类型这些维度都不预聚合，查询时通过 `JOIN content`（以及 `content_author`）实时汇总。
- 不建 JPA 实体，读写全部用 `JdbcTemplate`。
- 这套 OpenGauss 运行在 A 兼容模式，`DATE` 列实际存的是 `timestamp`（记录在 Plan 1 的修正记录里）。所以读取日期时用 `getTimestamp(...).toLocalDateTime().toLocalDate()`，不要用 `getObject(…, LocalDate.class)`；按天截断一律用 `date_trunc('day', …)`，**不要**用 `CAST(… AS DATE)`——后者在 A 兼容模式下会保留时间部分，把同一天的数据分成多组。实现时要先用 `gsql` 验证这两点。

## 4. 汇总与重算

`PvSummaryService.rebuild(LocalDate from, LocalDate to)`，在一个事务里完成：
1. `DELETE FROM pv_summary_daily WHERE summary_date` 落在范围内（`from`/`to` 任一为空表示该侧不设界）；
2. `INSERT INTO pv_summary_daily SELECT content_id, date_trunc('day', view_time), count(*) FROM pv_log WHERE view_time` 落在同一范围内 `GROUP BY content_id, date_trunc('day', view_time)`。范围用 `view_time >= from 当天零点 AND view_time < to 次日零点` 表达，不对列做函数运算。

返回写入的行数。先删后插，所以同一范围重复重算结果完全一样。

触发方式：
- **定时**：`PvSummaryScheduler`，`@Scheduled(cron = "${analytics.pv.rebuild-cron:0 10 0 * * *}")`，每次重算 `[今天 − (N−1), 今天]`，`N` 来自 `analytics.pv.rebuild-days`（默认 3）。受 `analytics.pv.schedule.enabled`（默认 true）控制；测试资源里设为 false，避免定时任务干扰测试。
- **手动**：`POST /api/admin/analytics/pv/rebuild?from=2026-01-01&to=2026-03-31`，两个参数都可省略；全省略表示重算全部历史（首次灌数据用）。响应 `{"from":…,"to":…,"rowsWritten":n}`。

## 5. 趋势接口

`GET /api/analytics/pv/trend`

| 参数 | 说明 |
|---|---|
| `granularity` | `DAY` / `WEEK` / `MONTH` / `YEAR`，必填 |
| `from`、`to` | 日期（`yyyy-MM-dd`），必填 |
| `channelId`、`accountId`、`employeeId`、`contentType` | 过滤条件，全部可选，可任意组合（取交集），例如"半导体频道下视频类内容" |
| `forecastPeriods` | 预测期数，默认 1，范围 0~12（0 表示不要预测） |

响应：

```json
{
  "granularity": "MONTH",
  "from": "2026-01-01",
  "to": "2026-06-30",
  "points": [
    {"periodStart": "2026-01-01", "pv": 1200, "momRate": 0.05, "yoyRate": null, "complete": true}
  ],
  "forecast": [
    {"periodStart": "2026-07-01", "value": 1180.5, "lower": 1010.2, "upper": 1350.8}
  ]
}
```

响应里的 `from`/`to` 原样回显请求值；`points` 覆盖的是按 5.1 扩展后的完整周期，所以第一个点的 `periodStart` 可能早于 `from`。

### 5.1 分桶与补零
- 数据库只按天取数（`pv_summary_daily JOIN content`，带上过滤条件），分桶在 Java 里做：周一为一周起点（ISO），月取 1 号，年取 1 月 1 日。
- `from` 向前扩展到所在周期的起点，`to` 向后扩展到所在周期的终点，但**不超过今天**（`to` 超过今天时按今天算）。这样每个周期都是完整的数据，不会出现"只统计了半周"的点。
- 没有浏览的周期补 0，保证序列连续（移动平均和图表都需要）。
- 过滤条件指向不存在的 id 时不报错，返回全 0 的序列。

### 5.2 环比与同比
- `momRate = (当期 − 上一期) / 上一期`，用小数表示（0.05 即 +5%）；第一个点或上一期为 0 时为 `null`。
- `yoyRate = (当期 − 去年同期) / 去年同期`；去年同期规则：DAY 取去年同一天（2 月 29 日取 2 月 28 日），WEEK 取 52 周前，MONTH 取 12 个月前，YEAR 取去年；去年同期为 0 或没有数据时为 `null`。
- 为了让返回范围内最早的点也能算出同比，服务自动把查询范围再往前多取一年（起点 = 请求起点所在周期的起点 − 1 年 − 1 周，多出的一周保证 WEEK 粒度 52 周前的周期也在数据里），计算完只返回请求范围内的点。

### 5.3 未完成周期
周期的最后一天早于今天才算 `complete=true`。今天、本周、本月、今年这类还没结束的周期在序列里照常显示，但标 `complete=false`，**不参与预测**——否则会把预测整体拉低。

### 5.4 预测
设计文档原来写的是"移动平均叠加最近的环比增长率"。环比增长率在数据量小或波动大时很不稳定，所以改用"移动平均自身的漂移"，规则如下：
- 窗口 `N`：DAY 7，WEEK 4，MONTH 3，YEAR 2。只使用 `complete=true` 的点，取最近 `N` 个。这里的"点"指扩展后序列（包含为同比向前多取的那一年）里的已完成周期，不局限于请求范围，所以请求范围很短时也能给出预测。
- `ma` = 这 `N` 个值的均值。
- `slope = (ma − 再往前 N 个值的均值) / N`；已完成的点少于 `2N` 个时 `slope = 0`。
- 第 `k` 期预测值 = `max(0, ma + slope × k)`，`k = 1..forecastPeriods`。预测从"最后一个已完成周期"的下一个周期开始，所以第 1 期可能正好是正在进行中的那个周期（"今天最终会收在多少"）。
- 置信区间 = 预测值 ± `σ`，`σ` 是这 `N` 个值的样本标准差（`N` 个值中只有 1 个时 `σ = 0`）；下限取 `max(0, …)`。
- 已完成的点少于 `N` 个时不给预测，`forecast` 返回空数组。

## 6. Top N 接口

`GET /api/analytics/pv/top`

| 参数 | 说明 |
|---|---|
| `dimension` | `CHANNEL` / `ACCOUNT` / `EMPLOYEE` / `CONTENT_TYPE`，必填 |
| `from`、`to` | 日期，必填，不做周期扩展，按实际日期范围统计 |
| `limit` | 默认 10，范围 1~100 |
| `channelId`、`accountId`、`employeeId`、`contentType` | 同趋势接口的可选过滤条件 |

响应：`[{"id": 7, "name": "半导体", "pv": 3456}]`，按 PV 降序，PV 相同按 id 升序。`CONTENT_TYPE` 维度的 `id` 为 null，`name` 为 `ARTICLE` / `VIDEO`。`limit` 校验为整数后以字面量写进 SQL（OpenGauss 不接受绑定参数形式的分页，Plan 1 已踩过）。

**博主维度的归属规则**：一篇内容有多个共同作者时，每位作者都记完整的 PV（浏览是不可拆分的计数）。因此所有博主的 PV 之和会大于全站总 PV，接口说明里要写明。`content_author` 允许出现同一个人的重复行（团队账号随机选成员是有放回的），所以：博主过滤用 `EXISTS (SELECT 1 FROM content_author …)`，博主 Top N 用 `JOIN (SELECT DISTINCT content_id, employee_id FROM content_author)`，避免重复计数。

## 7. 组件划分

包名 `com.jiaxian.analytics.pv`：

| 组件 | 职责 | 依赖 |
|---|---|---|
| `PvSummaryService` | 重算汇总表（事务内先删后插） | `JdbcTemplate` |
| `PvSummaryScheduler` | 按 cron 触发最近 N 天重算 | `PvSummaryService` |
| `PvDailyQueryRepository` | 按过滤条件取"按天 PV"；按维度取 Top N | `JdbcTemplate` |
| `PvSeriesBuilder` | 纯逻辑：分桶、补零、周期扩展 | 无 |
| `PvTrendAnalyzer` | 纯逻辑：环比、同比、预测和置信区间 | 无 |
| `PvAnalyticsService` | 编排：校验参数、多取一年数据、调用上面几个、裁剪结果；持有 `Clock` 以便测试固定"今天" | 上面几个 |
| `PvAnalyticsController` | `/api/analytics/pv/trend`、`/top`；参数非法返回 400 | `PvAnalyticsService` |
| `PvAdminController` | `/api/admin/analytics/pv/rebuild` | `PvSummaryService` |

## 8. 错误处理

- 未知的 `granularity`/`dimension`/`contentType`、日期格式错误、`from` 晚于 `to`、`forecastPeriods`/`limit` 越界：HTTP 400，响应体是一句说明原因的文字。
- 过滤条件指向不存在的实体：不是错误，返回空/全 0 结果。
- 重算接口 `from` 晚于 `to`：HTTP 400。

## 9. 测试策略

- **纯逻辑单元测试**：`PvSeriesBuilder`（周一起点、月/年边界、补零、周期扩展、不超过今天）和 `PvTrendAnalyzer`（环比/同比、去年同期缺失、各粒度的预测窗口、`slope`、置信区间、点不足时不预测、未完成周期被排除），全部用手算的具体数字断言，固定 `Clock`。
- **对真实 OpenGauss 的集成测试**：重算的幂等性和日期范围语义；各过滤条件的组合；合著内容对每位作者记完整 PV；重复的 `content_author` 行不造成重复计数；四个维度的 Top N 顺序和名称；`date_trunc` 的行为。
- **接口测试**：参数校验和 400 响应；响应结构。
- **端到端**：历史回填 → 重算 → 通过 HTTP 调用趋势和 Top N；核心一致性断言是"汇总表 `pv_count` 的总和 = 同一日期范围内 `pv_log` 的行数"。
- **手工验证**：回填时把 `historyStart` 设成两年以前，才看得到同比。

## 10. 明确不做的范围（Out of Scope）

- 前端页面（图表展示留给前端看板那份计划）。
- 消费 Kafka 事件实时更新汇总表——实时数据靠定时重算最近几天补进来，当天的数据有最多一天的延迟，需要时手动调重算接口。
- 积分排行榜、KOL/RFM 分层。
- 更复杂的预测模型（ARIMA、Prophet）和随预测期数变宽的置信区间——置信区间对所有预测期都用同一个 `σ`。
- 接口鉴权（沿用项目里其他管理接口的做法，不做）。
