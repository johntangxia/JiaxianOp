# 个人博主详情页接口 · 设计方案

- 日期：2026-10-04
- 状态：已实现（实施计划见 [个人博主详情页接口](../plans/2026-10-04-blogger-profile.md)）
- 前置依赖：[KOL 分层与流失预警](2026-10-04-kol-segmentation-design.md)（每周保留的 `blogger_rfm_score`、`churn_alert`、建议文案库）、[PV 多维度聚合](2026-10-04-pv-analytics-design.md)（复用 `Granularity` 枚举、`Clock` Bean 和"SQL 按天取数、Java 分桶补零"的做法）。

## 1. 背景与目标

母设计文档第 6 节的"个人博主详情页"需要：分层标签 + 预警徽标 + 匹配建议文案；活跃时间轴（发帖/评论/登录事件分布）；积分构成趋势（主动 vs 被动堆叠图，按周/月）；RFM 分数历史走势（分层标签变化轨迹）。第 7 节规定入口是 `GET /api/bloggers/{id}/profile`。本阶段交付这套后端接口。

## 2. 范围

**包含**：三个只读接口、对应的取数与组装逻辑、纯逻辑的"标签变化点"计算。

**不包含**（第 8 节）：前端、鉴权、缓存、预警处理流转、对任意员工实时计算 RFM。

## 3. 接口

图表需要切换粒度，如果都塞进一个接口，每次切换都要重新加载整页数据，所以拆成三个。员工不存在统一返回 404；任何员工都能查，不要求在 TOP N 名单里（名单外的员工没有分层信息，只有基本信息和两个图表）。

### 3.1 `GET /api/bloggers/{id}/profile?weeks=26`

`weeks` 默认 26，范围 1~104。响应：

```json
{
  "employee": {"id": 3601, "name": "员工-1", "dept": null, "joinDate": null},
  "current": {
    "asOf": "2026-03-01", "rank": 7, "segment": "CHURN_RISK", "segmentName": "流失预警",
    "churnAlert": true, "recommendation": "运营同学私信关怀……",
    "rollingPoints12m": 5200, "recencyDays": 26, "frequency": 12, "monetary": 480,
    "rScore": 2, "fScore": 5, "mScore": 5,
    "alert": {"metric": "ACTIVITY", "recentDaily": 0.0, "baselineDaily": 0.52, "dropRate": 1.0}
  },
  "history": [
    {"asOf": "2025-12-07", "rank": 5, "segment": "STAR_KOL", "segmentName": "明星KOL",
     "churnAlert": false, "rScore": 5, "fScore": 5, "mScore": 5}
  ],
  "segmentChanges": [{"asOf": "2026-01-18", "from": "STAR_KOL", "to": "CHURN_RISK"}],
  "recentEvents": [{"time": "2026-02-03T10:12:00", "type": "LOGIN", "refId": null}]
}
```

- **`current`**：取全局最近一次重算（`latestAsOf`）里这个人的那一行，并联上同一统计日的 `churn_alert` 取预警详情。该员工不在最近一次名单里（或还没有任何重算记录）时为 `null`；`alert` 只在触发预警时有值，否则为 `null`。`recommendation` 用文案库的查找规则（先精确匹配，再回退到该标签的无预警文案，都没有则为 `null`）。
- **`history`**：该员工在"最近 `weeks` 次每周重算"的窗口里的每次重算结果，按统计日升序。窗口起点 = `latestAsOf − 7 × (weeks − 1)` 天（含），终点 = `latestAsOf`；没有任何重算记录时为空数组。
- **`segmentChanges`**：由 `history` 相邻两期标签不同的位置派生（纯逻辑），每个变化点记录发生变化那一期的 `asOf`、变化前和变化后的标签；没有变化则为空数组。
- **`recentEvents`**：最近 20 条发帖/评论/登录（`active_action_log`），按时间倒序（同一时间按 id 倒序），`type` 是 `POST`/`COMMENT`/`LOGIN`，`refId` 可为 `null`。

### 3.2 `GET /api/bloggers/{id}/activity?granularity=&from=&to=`

活跃时间轴：发帖、评论、登录在各周期里的次数分布。

```json
{"granularity": "WEEK", "from": "2025-12-15", "to": "2026-06-10",
 "points": [{"periodStart": "2025-12-15", "posts": 1, "comments": 0, "logins": 4, "complete": true}]}
```

### 3.3 `GET /api/bloggers/{id}/points-trend?granularity=&from=&to=`

积分构成趋势：各周期的主动分、被动分（用来画堆叠图）。

```json
{"granularity": "WEEK", "from": "2025-12-15", "to": "2026-06-10",
 "points": [{"periodStart": "2025-12-15", "activePoints": 380, "passivePoints": 42, "total": 422, "complete": true}]}
```

### 3.4 3.2 与 3.3 的共同规则

- `granularity` 为 `DAY`/`WEEK`/`MONTH`/`YEAR`（大写，复用 `Granularity`），省略为 `WEEK`；`to` 省略为今天；`from` 省略时按粒度往前取：DAY 30 天、WEEK 26 周、MONTH 12 个月、YEAR 5 年（即 `to` 往前推 29 天 / 25 周 / 11 个月 / 4 年，再扩展到所在周期起点）。
- `from` 向前扩展到所在周期的起点；`to` 超过今天按今天算；没有数据的周期补 0；周期的最后一天早于今天才标 `complete=true`。响应里的 `from`、`to` 回显**解析默认值之后**的实际请求日期。
- 一次最多 400 个周期，超出返回 400。
- 数据库只按天取数（`active_action_log` 按 `date_trunc('day', occur_time)` 和类型分组计数；`points_ledger` 按天和 `category` 分组求和），分桶在 Java 里做。**不要**用 `CAST(… AS DATE)`（A 兼容模式下会保留时间部分），日期参数一律绑定 `Timestamp`。
- 范围整个在未来（`from` 晚于今天）时返回空数组，不查库。

## 4. 错误处理

- 员工不存在：HTTP 404，响应体是一句文字。
- 未知的 `granularity`、日期格式错误、`from` 晚于 `to`、`weeks` 越界、周期数超过 400、路径里的 id 不是数字：HTTP 400，响应体是一句说明原因的文字。
- 没有数据的周期、没有重算记录、员工不在名单里：不是错误，按上面各接口的说明返回空值或补 0。

## 5. 组件划分

放在现有的 `com.jiaxian.analytics.kol` 包里：

| 组件 | 职责 |
|---|---|
| `SegmentTrajectory`（纯） | 从 `history` 算出标签变化点 |
| `PeriodAxis`（纯） | 给定粒度、`from`、`to`、今天，生成连续周期（起点 + 是否已结束），`activity` 和 `points-trend` 共用 |
| `BloggerProfileRepository` | 员工基本信息；当前那一行（联预警详情）；窗口内的历史行；按天的活跃次数和积分；最近事件 |
| `BloggerProfileService` | 校验参数、解析默认值、组装 `profile`、分桶补零，持有 `Clock` |
| `BloggerProfileController` | 三个接口，路径 `/api/bloggers/{id}/…` |
| 记录类 | `EmployeeInfo`、`AlertDetail`、`CurrentStanding`、`HistoryPoint`、`SegmentChange`、`RecentEvent`、`BloggerProfile`、`ActivityPoint`、`ActivitySeries`、`PointsTrendPoint`、`PointsTrend` |
| `EmployeeNotFoundException` | 404；并入现有的 `KolExceptionHandler`（它的 `assignableTypes` 加上新控制器） |

## 6. 测试策略

- **纯逻辑单测**：`SegmentTrajectory`（无变化、多次变化、只有一期、空列表）；`PeriodAxis`（周起点扩展、`to` 超过今天被截断、未来范围为空、未结束周期标记）。
- **对真实 OpenGauss 的集成测试**：2019 年夹具，数字手算——按天活跃次数（只统计发帖/评论/登录、其他员工的行为不串）、按天主动/被动积分、最近事件的排序和 `limit`、`current` 行联预警详情、窗口内历史行的截取、员工不存在。
- **服务层单测**（Mockito + 固定 `Clock`）：`profile` 的各种情形（员工不存在 / 没有重算记录 / 有记录 / 员工不在最近名单里 / `weeks` 越界）、默认范围、粒度扩展、周期数上限、标签变化点的组装。
- **接口测试**：真实 HTTP，覆盖 404/400 和响应结构；用临时创建、用完即删的员工验证 `activity`/`points-trend` 的响应。
- **端到端**：开画像回填并补多周重算后，取一个流失者的详情：`current` 是流失预警且带预警详情，`history` 升序，`segmentChanges` 里有变成流失预警的变化点；活跃次数合计等于该员工在 `active_action_log` 里的行数，积分合计及主动/被动拆分都等于 `points_ledger`；`recentEvents` 共 20 条且按时间倒序。

## 7. 明确不做的范围（Out of Scope）

- 前端页面。
- 接口鉴权。
- 缓存。
- 预警处理流转。
- 对任意员工实时计算 RFM（只展示已落库的历史）。
