# KOL 分层与流失预警 · 设计方案

- 日期：2026-10-04
- 状态：已通过讨论确认
- 前置依赖：[基础设施与数据模型](2026-09-23-community-analytics-design.md)（`active_action_log`、`points_ledger`）、[PV 多维度聚合](2026-10-04-pv-analytics-design.md)（复用 `Clock` Bean 和 JdbcTemplate/Timestamp 约定）、[积分排行榜](2026-10-04-leaderboard-design.md)。
- 后续：个人博主详情页接口（活跃时间轴、积分构成趋势、RFM 历史轨迹）放到下一份计划（Plan 6），本阶段保留的每周 RFM 历史就是为它准备的。

## 1. 背景与目标

母设计文档第 6 节要求对积分 TOP 1000 的博主做 RFM 分层、流失预警和运营建议。本阶段交付：每周计算的 TOP N 名单与 RFM 打分、分层标签、流失预警记录、建议文案库（可维护）、名单查询接口，并给模拟器加"博主画像"，让每个分层和预警都能在模拟数据里真实出现并被验证。

## 2. 范围

**包含**：
- 表 `blogger_rfm_score`、`churn_alert`、`action_playbook`
- 每周重算（定时 + 管理员手动，可补历史周）
- TOP N 名单查询（按分层/预警筛选、分页，附建议文案和各分层人数汇总）
- 建议文案库的增删改查
- 模拟器的博主画像（可选开关，默认关闭）

**明确不包含**（第 9 节）：个人博主详情页接口、前端、预警处理流转、数据库配置表、Redis、鉴权。

## 3. 计算口径

所有指标以"统计日 `asOf`"为基准：默认今天，可传参（用来补历史周、验证）。时间窗口都按天对齐，右端开区间 `< asOf 次日零点`。

| 窗口 | 范围 |
|---|---|
| 12 个月（名单） | `[asOf 次日零点 − 1 年, asOf 次日零点)` |
| 近 90 天（R/F/M） | `[asOf − 89 天, asOf]`（天数 `window-days`，默认 90） |
| 近期（预警） | `[asOf − 29 天, asOf]`（天数 `recent-days`，默认 30），记起点为 `recentStart` |
| 基线（预警） | `[recentStart − 90 天, recentStart)`（天数 `baseline-days`，默认 90） |
| 上一个近期（成长趋势） | `[recentStart − 30 天, recentStart)` |

- **名单**：12 个月窗口内 `points_ledger` 积分之和排名前 N（`top-n`，默认 1000）。积分相同按员工 id 升序，没有积分的不入选。`rank_no` 是名单内名次（1 起，不并列）。
- **R**（Recency）：距 `asOf` 最近一次发帖/评论/登录（`active_action_log` 中 `POST`/`COMMENT`/`LOGIN`）的天数；取 `asOf` 当天及之前的全部历史；一次都没有则为空，打分时按最差处理。
- **F**（Frequency）：近 90 天上述三类行为的次数。
- **M**（Monetary）：近 90 天 `points_ledger` 积分之和（主动 + 被动）。
- **五分位打分**：对名单内的人比较。
  - F、M：`分数 = min(5, 1 + floor(严格小于自己的人数 / 总人数 × 5))`。
  - R：天数越小越好，`分数 = min(5, 1 + floor(严格大于自己的人数 / 总人数 × 5))`，R 为空视为无穷大。
  - 数值相同的人分数相同；全员数值相同则全员 1 分。
- **流失预警**：近期和基线分别折算成"日均"（近期总数 ÷ `recent-days`，基线总数 ÷ `baseline-days`），**活跃次数**（三类行为）和**积分**两个指标分别判断；基线日均为 0 的指标不参与。任一指标的下降比例 `1 − 近期日均 / 基线日均` 严格大于 `churn-drop-threshold`（默认 0.5）即触发；两个指标都触发时记下降更多的那个（并列记活跃次数）。

## 4. 分层规则

按顺序判断，命中即停。`avg = (R分 + F分 + M分) / 3`。

| 顺序 | 标签 | 判定 |
|---|---|---|
| 1 | `CHURN_RISK` 流失预警 | 触发预警，并且上一期标签是 `STAR_KOL`/`STEADY`/`CHURN_RISK`；没有上一期时用 `avg ≥ 3` 代替 |
| 2 | `STAR_KOL` 明星 KOL | 无预警，且 `avg ≥ 4` |
| 3 | `RISING` 成长潜力新人 | `R分 ≥ 4`，`F分 ≤ 2`，`M分 ≤ 2`，且近期积分 > 上一个近期积分 |
| 4 | `STEADY` 稳定贡献者 | 无预警，且 `3 ≤ avg < 4` |
| 5 | `DORMANT` 沉寂用户 | `R分 ≤ 2`，`F分 ≤ 2`，`M分 ≤ 2` |
| 6 | `OBSERVE` 观察中 | 其余情况（母设计文档的表有空隙，补此兜底标签） |

`RISING` 排在 `STEADY` 之前，是因为 R=5、F=2、M=2 的平均分恰好是 3.0，若先判稳定贡献者，典型的"近期活跃但产出低、趋势上升"的新人会被吞掉；`STAR_KOL` 要求 avg ≥ 4，与 F、M 分都 ≤ 2 互斥（最高 3.0），所以 `RISING` 与 `STAR_KOL` 的先后无影响。

"上一期"= `as_of_date` 小于本次且最大的那次重算里的标签；该员工当时不在名单里则视为没有上一期。

## 5. 数据模型

追加到 `schema.sql`，对员工的外键都 `ON DELETE CASCADE`（回填会 `deleteAll()` 全部员工）。`DATE` 列在 A 兼容模式下实际是 `timestamp`：写入绑定 `Timestamp`，读取用 `getTimestamp(…).toLocalDateTime().toLocalDate()`。

```sql
CREATE TABLE IF NOT EXISTS blogger_rfm_score (
    as_of_date         DATE NOT NULL,
    employee_id        BIGINT NOT NULL REFERENCES employee(id) ON DELETE CASCADE,
    rank_no            INT NOT NULL,
    rolling_points_12m BIGINT NOT NULL,
    recency_days       INT,
    frequency          INT NOT NULL,
    monetary           BIGINT NOT NULL,
    r_score            INT NOT NULL,
    f_score            INT NOT NULL,
    m_score            INT NOT NULL,
    segment_label      VARCHAR(20) NOT NULL,
    churn_alert        BOOLEAN NOT NULL,
    PRIMARY KEY (as_of_date, employee_id)
);

CREATE TABLE IF NOT EXISTS churn_alert (
    alert_date      DATE NOT NULL,
    employee_id     BIGINT NOT NULL REFERENCES employee(id) ON DELETE CASCADE,
    metric          VARCHAR(10) NOT NULL CHECK (metric IN ('ACTIVITY', 'POINTS')),
    recent_daily    DOUBLE PRECISION NOT NULL,
    baseline_daily  DOUBLE PRECISION NOT NULL,
    drop_rate       DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (alert_date, employee_id)
);

CREATE TABLE IF NOT EXISTS action_playbook (
    id                  BIGSERIAL PRIMARY KEY,
    segment_label       VARCHAR(20) NOT NULL,
    alert_status        VARCHAR(10) NOT NULL CHECK (alert_status IN ('NO_ALERT', 'ALERT')),
    recommendation_text VARCHAR(1000) NOT NULL,
    UNIQUE (segment_label, alert_status)
);
```

- 与母设计文档的差异：`period` 改叫 `as_of_date`；`churn_alert` 没有 `status` 字段（处理流转不在本阶段，只记触发历史）；多了 `metric`（触发的是哪个指标）。
- 每周的 `blogger_rfm_score` 永久保留，Plan 6 用它画"标签变化轨迹"。
- `action_playbook` 的种子数据放进 `data.sql`，用 `ON DUPLICATE KEY UPDATE recommendation_text = recommendation_text` 保证重启不覆盖运营改过的文案。种子：`STAR_KOL/NO_ALERT`、`CHURN_RISK/ALERT`、`RISING/NO_ALERT`、`DORMANT/NO_ALERT`（均取自母设计文档示例）以及补充的 `STEADY/NO_ALERT`、`OBSERVE/NO_ALERT`。
- **文案查找**：先 `(标签, 当前预警状态)` 精确匹配，再回退到 `(标签, NO_ALERT)`，都没有则为空。

## 6. 重算与触发

`KolRebuildService.rebuild(asOf)`：一个事务内先删本次 `asOf` 的 `blogger_rfm_score`/`churn_alert` 旧行，再计算并批量写入；返回 `{asOf, population, alerts}`。重算更早的周不会连带更新更晚的周（"上一期"链条以重算时为准）。

`rebuildWeeks(asOf, weeks)`：从旧到新依次重算 `asOf − 7×(weeks−1)` … `asOf`，让"上一期标签"链条成立，用来补历史。

- **定时**：`KolScheduler`，`@Scheduled(cron = "${analytics.kol.rebuild-cron:0 0 1 * * MON}")` 每周一凌晨算 `asOf=今天`；受 `analytics.kol.schedule.enabled`（默认 true，测试里 false）控制。
- **手动**：`POST /api/admin/analytics/kol/rebuild?asOf=2026-03-01&weeks=12`，两个参数都可省略（默认今天、1 周）；`asOf` 晚于今天或 `weeks` 不在 1~52 返回 400。

## 7. 接口

- `GET /api/bloggers/top1000?asOf=&segment=&alert=&limit=50&offset=0`
  - `asOf` 省略取最近一次重算；`segment` 为 `STAR_KOL` 等枚举（大写）；`alert` 为 true/false；`limit` 1~1000，`offset` ≥ 0。排序按 `rank_no`。
  - 响应：`{asOf, total, segmentCounts:{STAR_KOL:n,…}, items:[{employeeId,name,rank,rollingPoints12m,recencyDays,frequency,monetary,rScore,fScore,mScore,segment,segmentName,churnAlert,recommendation}]}`；`total` 是筛选后的总数，`segmentCounts` 是该次重算未筛选的各分层人数；没有任何重算记录时 `asOf` 为 null、列表为空。
- `GET /api/admin/playbook`、`POST /api/admin/playbook`、`PUT /api/admin/playbook/{id}`、`DELETE /api/admin/playbook/{id}`：文案库维护。创建/更新校验标签和预警状态枚举、文案非空且 ≤ 1000 字；`(标签, 预警状态)` 重复返回 409；不存在的 id 返回 404；参数非法返回 400。
- 错误响应体一律是一句说明原因的文字。

## 8. 模拟画像（改动 Plan 1 的回填）

`SimulationConfig` 新增可选字段 `personas`（默认 `false`，默认行为完全不变）；回填接口的请求体同样新增可选 `personas`。开启后，员工被确定性地分到下列画像之一（用独立的随机流，不扰动主随机序列）：

| 画像 | 比例 | 登录规律（`end` = `historyEnd`） |
|---|---|---|
| `STAR` 持续活跃 | 10% | 全程每天约 80% 的概率登录一次 |
| `RISING` 新人 | 10% | 只在 `end−20`、`end−4` 各登录一次 |
| `CHURNING` 流失者 | 10% | 与 `STAR` 一样，但 `end−60` 之后不再登录 |
| `DORMANT` 沉寂者 | 20% | 在 `[historyStart, end−120)` 内随机登录 8 次，之后不登录 |
| `RANDOM` | 其余 | 现有随机登录逻辑 |

- 各比例按 `round(员工数 × 比例)` 取整，分配靠 `Random(randomSeed ^ 常量)` 打乱员工序号后依次切出，相同种子和员工数得到相同结果。
- 只控制**登录**：登录有 20 分且计入 F，是最容易控制 R/F/M 的杠杆；发帖、互动保持原有随机逻辑。
- 画像不落库：它是 `(员工数, 种子)` 的纯函数，测试可以按同样的函数重算出对应关系。
- 画像参数是经验值，端到端测试会检验"各画像确实落入预期分层"，必要时调整常量。

## 9. 配置（`application.yml`，修改后重启生效）

```yaml
analytics:
  kol:
    top-n: 1000
    window-days: 90
    recent-days: 30
    baseline-days: 90
    churn-drop-threshold: 0.5
    rebuild-cron: "0 0 1 * * MON"
    schedule:
      enabled: true
```

## 10. 组件划分

包 `com.jiaxian.analytics.kol`：

| 组件 | 职责 |
|---|---|
| `Segment` | 分层枚举（含中文名） |
| `KolProperties` | 配置绑定 |
| `BloggerMetrics`、`RfmScores`、`ChurnSignal`、`KolRebuildResult` 等记录 | 数据载体 |
| `RfmScorer`（纯） | 五分位打分 |
| `ChurnDetector`（纯） | 流失预警判定 |
| `SegmentClassifier`（纯） | 分层规则 |
| `KolMetricsRepository` | 名单与各窗口指标取数 |
| `KolRfmRepository` | 写入/删除每周结果；上一期标签；最近一次重算日期 |
| `KolRebuildService`、`KolScheduler` | 重算与定时 |
| `PlaybookRepository`、`PlaybookService` | 文案库读写与查找 |
| `BloggerListRepository`、`BloggerListService` | 名单查询 |
| 三个 Controller + `KolExceptionHandler` | HTTP 接口与错误映射（400/404/409） |

模拟器侧（包 `com.jiaxian.analytics.simulator`）：`BloggerPersona`、`PersonaAssigner`、`PersonaLoginPlanner`，以及对 `SimulationConfig`、`HistoricalBackfillService`、`SimulatorController` 的小改动。

## 11. 测试策略

- **纯逻辑单测**（手算数字）：`RfmScorer`（五分位边界、并列同分、全员相同、R 为空）、`ChurnDetector`（阈值边界、基线为 0、两个指标选更大者）、`SegmentClassifier`（六条规则各一条、优先级、冷启动回退）。
- **对真实 OpenGauss 的集成测试**：指标 SQL 的窗口边界与"只统计三类行为"；名单按 12 个月积分排序与 Top N 截断；重算幂等；连续两周的"上一期标签"链条；预警行落库；名单接口筛选/分页/汇总；文案库增删改查与 409/404；员工删除级联清空。
- **画像单测**：分配比例与确定性；各画像登录日期的形状（`STAR` 的登录比例、`CHURNING` 在截止日后为 0、`RISING` 恰好两次、`DORMANT` 都在 `end−120` 之前）。
- **端到端**：开画像回填 → 补多周重算 → 通过 HTTP 取名单，断言各画像落入预期分层（流失者触发预警并标为流失预警、明星大多是明星 KOL、沉寂者不会被判为明星或稳定）。

## 12. 明确不做的范围（Out of Scope）

- 个人博主详情页接口（Plan 6）、前端页面。
- 预警处理流转（派单、已处理等状态）。
- 数据库配置表和阈值在线修改（参数走配置文件）。
- Redis 缓存、Kafka 消费者改动。
- 接口鉴权。
