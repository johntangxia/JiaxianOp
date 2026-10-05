# 稼先社区运营数据分析与积分KOL管理系统 · 设计方案

- 日期：2026-09-23
- 状态：已实现（按各子设计和实施计划分阶段落地，运行方式和文档索引见根目录 [README](../../../README.md)）

## 1. 背景与目标

稼先社区是公司内网的知识分享/讨论社区（类似今日头条形态：关注/推荐/热点/视频/直播/AI学习等综合频道，以及终端、移动无线、固定网络、半导体、IT计算、智能汽车、知识产权、制造、供应链等专业领域频道；账号可聚合发布文章/视频，员工可关注账号）。

社区本身（内容/频道/账号体系）与积分规则已在正式系统中存在。**本项目是一个独立的模拟/验证项目**：用模拟数据复现社区的运营场景，重点打磨"运营数据分析监控"和"积分驱动的KOL管理"这两块能力，验证指标口径、算法逻辑和技术架构是否可行，产出的代码将作为正式系统开发团队的设计与开发参考。

核心目标：
1. 从频道、账号、博主（员工）等维度长期监控内容浏览量（PV），支持趋势分析和简单预测。
2. 实现员工个人积分体系（主动行为积分 + 被动互动积分），支撑月度/年度"最热博主"排行榜（按当期有效积分而非历史累计排名）。
3. 针对积分 TOP1000 的博主做个人行为分析，类比零售行业客户分层管理（RFM模型），实现分层、流失预警和运营建议，支撑 KOL 管理决策。

## 2. 总体架构

批流混合架构：历史数据批量回填，"运营中"的新增数据走 Kafka 事件流，定时任务做预聚合，Redis 承载实时缓存与近实时排行榜。

```
┌───────────────┬───────────────┬───────────────┬─────────────────┐
│  数据模拟器      │  流处理消费者    │  聚合调度任务     │   BI看板前端      │
│ (Simulator)    │ (Consumer)    │ (Aggregator)  │  (Vue3+TS)      │
├───────────────┼───────────────┼───────────────┼─────────────────┤
│ 历史批量回填     │ 消费Kafka事件   │ 日/月/年汇总表    │ 多维PV监控       │
│ 实时事件生产      │ 写明细表        │ 排行榜快照生成    │ 趋势预测图        │
│  → Kafka       │ 更新Redis计数器  │ RFM/预警计算     │ 积分/KOL排行榜     │
│               │               │ (Quartz定时)    │                 │
└───────────────┴───────────────┴───────────────┴─────────────────┘
```

数据流：
1. **历史回填**（一次性/可重跑，带 `simulation_batch_id` 标记）：模拟器按配置规模直接批量写 OpenGauss 明细表，不经过 Kafka。
2. **实时模拟**（持续运行，按时间加速比运行）：模拟器作为 Kafka Producer 持续产生发文/浏览/点赞/评论/关注/登录等事件。
3. **流处理消费**：Spring Boot Kafka Consumer 落明细表，同步用 `ZINCRBY` 等更新 Redis 实时计数器/排行榜 Sorted Set。
4. **定时聚合**（Quartz）：按日/周/月/年把明细表卷积成汇总表、排行榜快照、RFM分层结果，并刷新 Redis 缓存。
5. **BI看板查询**：Spring Boot REST API 优先读 Redis 缓存（实时榜单、首屏数据），历史趋势/多维分析查汇总表，不直接扫明细表。

技术栈：Vue3 + TypeScript（前端）、Spring Boot（后端API/消费者/定时任务/模拟器）、OpenGauss（明细表+汇总表+快照表）、Redis（实时计数器/排行榜缓存）、Kafka（事件异步传输）。移动端使用 JavaScript。

## 3. 数据模型

### 3.1 维度表

| 表 | 关键字段 | 说明 |
|---|---|---|
| `employee` | id, name, dept, join_date | 员工 |
| `channel` | id, name, category | 频道（综合频道 + 专业领域频道） |
| `account` | id, name, type(personal/team) | 账号；personal 与 employee 一对一，team 可多员工运营 |
| `account_member` | account_id, employee_id | 团队账号-员工关系（多对多） |
| `content` | id, type(article/video), channel_id, account_id, publish_time | 文章/视频 |
| `content_author` | content_id, employee_id | 内容-作者关系（支持多个共同作者） |
| `comment` | id, content_id, employee_id, parent_comment_id(nullable), create_time | 评论，支持二级回复（`parent_comment_id` 非空即为回复） |

### 3.2 事实表（明细事件，经 Kafka 落库）

| 表 | 关键字段 | 说明 |
|---|---|---|
| `pv_log` | content_id, viewer_employee_id, view_time | 浏览事件 |
| `engagement_log` | content_id/comment_id, actor_employee_id, action_type(like/share/favorite), occur_time | 互动事件 |
| `active_action_log` | employee_id, action_type(post/comment/login/share/favorite/like/view), ref_id, occur_time | 主动行为事件 |
| `editorial_pick_log` | content_id, pick_time | 入选思想简报（编辑精选）事件，由模拟器按热度规则定期模拟产生 |

### 3.3 积分相关表

| 表 | 关键字段 | 说明 |
|---|---|---|
| `points_weight_config` | action_type, category(active/passive), weight, effective_date | 行为→权重配置，后台可调，按生效日期版本化 |
| `points_ledger` | id, employee_id, action_type, category, points, ref_id(nullable), occur_time | 积分流水，排行榜计算的唯一事实来源 |

**积分权重表（已确认）**

主动行为积分（操作发起人得分）：发帖100、登录20、发布评论/回复评论15、分享15、收藏5、点赞3、浏览1。

被动行为积分（内容/评论作者得分，因他人互动触发）：入选思想简报（编辑精选）300、内容获得评论15、内容被分享15、评论获回复15、内容被收藏5、内容被点赞3、评论获赞3、内容被浏览1。

规则：一次点赞/分享/收藏/浏览/评论行为会**同时**产生两条积分流水——操作人一条（主动分）、内容/评论作者一条（被动分）。团队账号多作者内容产生的被动积分，按该内容的共同作者数**均分**后分别记账。评论支持二级回复（不需无限嵌套）：直接评论内容触发作者"获得评论"，回复某条评论触发原评论作者"评论获回复"，两种情况下回复/评论人本身都按"发布评论"计主动分。

### 3.4 汇总表（定时任务产出，看板直接查询）

| 表 | 粒度 | 说明 |
|---|---|---|
| `pv_summary_daily` | (channel_id/account_id/content_id, date) | 按天汇总PV，趋势图与同环比计算的基础 |
| `points_summary_monthly` / `points_summary_yearly` | (employee_id, period) | 员工当期（月/年）有效积分，主动/被动分开累计 |
| `leaderboard_snapshot` | (period_type, period_value, rank, employee_id, points) | 排行榜快照，每期定时任务跑完落一版，可追溯历史排行 |
| `blogger_rfm_score` | (employee_id, period, r_score, f_score, m_score, segment_label) | TOP1000博主RFM分层结果，按周计算 |
| `churn_alert` | employee_id, alert_date, recent_metric, baseline_metric, drop_rate, status | 流失预警记录 |
| `action_playbook` | segment_label, alert_status, recommendation_text | 分层×预警状态 → 建议文案，后台可维护 |

> 实现时的取舍（见 [PV 分析设计](2026-10-04-pv-analytics-design.md)）：`pv_summary_daily` 实际粒度是 (`content_id`, `summary_date`)，频道、账号、博主、内容类型这些维度都不预聚合，查询时通过 JOIN `content`（和 `content_author`）得到，所以任意维度的任意交叉筛选都不需要额外建表；汇总表可以随时重算（幂等），定时任务每天把最近几天补进来。

> 实现时的取舍（见 [榜单设计](2026-10-04-leaderboard-design.md)）：不建 `points_summary_monthly/yearly`，当期榜单查询时直接对 `points_ledger` 区间求和，`leaderboard_snapshot` 里同时带主动/被动拆分；`rank` 列实际命名为 `rank_no`（`rank` 是保留字）。

### 3.5 Redis 缓存 key 设计（示例）

- `rt:pv:channel:{channel_id}:today` — 频道今日实时PV计数器
- `leaderboard:month:{yyyyMM}` / `leaderboard:year:{yyyy}` — 当期排行榜（Sorted Set，实时更新+每日凌晨全量重算校准）
- `dashboard:overview` — 看板首屏聚合数据缓存

> 实现时的取舍：`leaderboard:month/year` Redis 实时榜未实现——榜单走"进行中实时求和 + 已结束读快照"，不改 Kafka 消费者。

## 4. 数据模拟器设计

**两种运行模式**：
1. **历史回填模式**（批处理，一次性/可重跑）：按配置的频道数、账号数（个人/团队比例）、员工数、历史时间跨度（默认近2年）等参数批量生成明细数据，直接写OpenGauss，不经Kafka。重跑前按 `simulation_batch_id` 清空旧模拟数据。
2. **实时模拟模式**（持续运行的Kafka Producer）：按可配置的时间加速比持续产生新事件，驱动"消费→聚合→缓存"整条链路，用于演示看板的近实时效果；管理员可调整生成速率、暂停/继续。

**真实感设计**：账号/内容热度呈幂律分布（模拟二八效应，验证"最热博主榜"能识别头部创作者）；PV时间序列叠加整体增长趋势+工作日/周末周期+随机噪声+偶发热点突增，用于验证同环比与移动平均预测在有波动场景下的稳健性。"入选思想简报"按热度规则从当期高热度内容中定期抽样模拟产生。

> 实现时的取舍（见 [数据基础设计](2026-10-05-data-foundation-design.md)）：频道体系改成仿今日头条的 5 个综合频道 + 23 个领域频道，内容只产生在领域频道；员工归属领域并有"加入社区日期"，加入前没有任何行为；周末/上班时段规律、半衰期约 2 天的浏览热度衰减、留存分型、同领域浏览偏好和沉默读者由 `ActivityModel` 与 `ViewerPicker` 统一建模。上面"增长趋势、偶发热点突增"仍未实现。

> 实现时的取舍（见 [行为数据设计](2026-10-05-behavior-data-design.md)）：浏览记录带来源（入口/搜索/分享/其他）和入口频道，自主入口与综合入口由入口频道推导；新增曝光、关注关系（不计分）、搜索日志、内容标签，来源占比和点击率取业务方给的现状值；`SchemaUpgrader` 在 Hibernate 之前先补列并执行幂等的 `schema.sql`。

## 5. 分析聚合与预测引擎

**多维PV分析**：支持按频道、账号、博主（员工）、内容类型（文章/视频）等维度，任意时间粒度（日/周/月/年）切片与交叉筛选。

**同比/环比/预测**：环比=(本期-上期)/上期；同比=(本期-去年同期)/去年同期；预测值=最近N期移动平均（日粒度7天/周粒度4周/月粒度3月）叠加最近环比增长率线性外推，附带基于历史波动率的置信区间，看板用虚线区分预测段与历史实际。

> 实现时的取舍（见 [PV 分析设计](2026-10-04-pv-analytics-design.md) 第 5.4 节）：预测改用"移动平均 + 移动平均自身的漂移"（`ma + slope × k`），而不是"叠加最近环比增长率"——环比增长率在数据量小或波动大时很不稳定；置信区间是预测值 ± 最近 N 个已完成周期的样本标准差，对所有预测期用同一个 σ；还没结束的周期（今天、本周、本月、今年）照常显示但不参与预测。分桶、补零、同环比和预测全部在 Java 里完成，数据库只负责按天取数。

**排行榜计算（区间制有效积分，非累计）**：
- 定时任务按周期（月/年）对 `points_ledger` 做 `SUM(points) GROUP BY employee_id WHERE occur_time BETWEEN period_start AND period_end`，写入 `leaderboard_snapshot`，历史每期快照永久保留。
- 进行中的当期榜单由 Redis Sorted Set 实时维护（Kafka消费者处理积分事件时 `ZINCRBY`），每日凌晨用明细表全量重算校准。
- 榜单展示当期积分的主动/被动构成拆分。

## 6. 个人博主行为分析与KOL管理

类比零售行业客户分层管理（RFM模型），把"消费金额/频次"映射为社区场景的"积分产出/活跃频次"。

**范围**：滚动近12个月累计积分排名前1000的员工，每周重算一次名单。

**RFM计算**（统计窗口：近90天）：
- R（Recency）= 距今最近一次「发帖/评论/登录」的天数
- F（Frequency）= 近90天「发帖+评论+登录」总次数
- M（Monetary）= 近90天积分总产出（主动+被动）

对TOP1000人群做五分位打分（各1-5分，R越近分越高）。

**流失预警**（独立判定，可叠加在分层结果上）：近30天活跃次数或积分产出，相比过去90天日均基线下降超过50%（默认阈值，后台可配），触发 `churn_alert`。

**分层规则**：

| 分层标签 | 判定条件 |
|---|---|
| 明星KOL | R/F/M平均分≥4，且未触发预警 |
| 稳定贡献者 | R/F/M平均分3~4之间，且未触发预警 |
| 成长潜力新人 | R分数高但F/M分数低，且近期趋势环比上升 |
| 流失预警 | 曾属于"明星KOL"或"稳定贡献者"，但触发了 `churn_alert` |
| 沉寂用户 | R分数低（长期未活跃）且F/M也低 |

**建议文案库**（`action_playbook`，后台可维护，示例）：

| 分层 | 预警状态 | 建议文案 |
|---|---|---|
| 明星KOL | 无预警 | 纳入"专家专栏"资源位重点推荐，邀请参与线下分享/评审活动，保持曝光激励 |
| 明星KOL/稳定贡献者 | 触发预警 | 运营同学私信关怀，了解活跃下降原因，推荐开放专属专栏权限或约稿邀请 |
| 成长潜力新人 | — | 加入新人扶持计划，给予首页曝光加权，缩短内容从发布到获得反馈的周期 |
| 沉寂用户 | — | 纳入低成本触达清单（邮件/群通知召回），不做重点人工投入 |

> 实现时的取舍（见 [KOL 分层设计](2026-10-04-kol-segmentation-design.md)）：分层表补了兜底标签"观察中"，"成长潜力新人"排在"稳定贡献者"之前判断；`blogger_rfm_score` 的 `period` 实际叫 `as_of_date` 并每周保留历史；`churn_alert` 没有 `status`，多了 `metric` 字段；阈值和参数放 `application.yml` 的 `analytics.kol.*`，建议文案库有完整增删改查；模拟器新增可选的博主画像（只影响登录）。个人博主详情页接口放到下一份计划。

**个人博主详情页**：分层标签+预警徽标+匹配建议文案；活跃时间轴（发帖/评论/登录事件分布）；积分构成趋势（主动vs被动堆叠图，按周/月）；RFM分数历史走势（分层标签变化轨迹）。

> 实现时的取舍（见 [博主详情页设计](2026-10-04-blogger-profile-design.md)）：详情页拆成三个接口——`GET /api/bloggers/{id}/profile`（基本信息、当前分层、RFM 历史轨迹、标签变化点、最近 20 条事件）、`/activity`（发帖/评论/登录次数的周期分布）、`/points-trend`（主动/被动积分堆叠趋势），后两者支持 DAY/WEEK/MONTH/YEAR 粒度切换；只展示已落库的每周重算历史，不对任意员工实时计算 RFM。

## 7. API 设计

下面是实际实现的接口（参数、响应和错误处理见各子设计文档）。参数非法统一返回 400 + 一句说明原因的文字，对象不存在 404，冲突 409。所有 `/api/admin/**` 接口没有鉴权（见第 10 节的范围说明），必须部署在内网。

**分析与查询**

| 接口 | 说明 |
|---|---|
| `GET /api/analytics/pv/trend` | PV 趋势：粒度（日/周/月/年）、日期范围、频道/账号/博主/内容类型过滤、环比、同比、预测与置信区间 |
| `GET /api/analytics/pv/top` | PV Top N：按频道/账号/博主/内容类型 |
| `GET /api/leaderboard/{month\|year}` | 月榜/年榜：进行中的周期实时计算，已结束的读快照（缺快照时回退实时计算） |
| `GET /api/leaderboard/{month\|year}/periods` | 榜单周期选择器的数据（有流水的周期 + 当前周期） |
| `GET /api/bloggers/top1000` | KOL 分层名单：按分层/预警筛选、分页，附建议文案和各分层人数 |
| `GET /api/bloggers/runs` | KOL 已有的重算统计日 |
| `GET /api/bloggers/{id}/profile` | 博主详情：当前分层、RFM 历史、标签变化点、最近事件 |
| `GET /api/bloggers/{id}/activity` | 博主活跃时间轴（发帖/评论/登录次数分布） |
| `GET /api/bloggers/{id}/points-trend` | 博主积分构成趋势（主动/被动） |
| `GET /api/dimensions/channels`、`/accounts`、`/employees` | 筛选与选择器用的频道、账号、员工选项（账号和员工支持关键字搜索） |
| `GET /api/meta/pv-range` | PV 汇总表里有数据的日期范围 |
| `GET /api/health` | 健康检查 |

**管理**

| 接口 | 说明 |
|---|---|
| `POST /api/admin/simulator/backfill` | 历史回填（会清空全部模拟数据及其派生数据） |
| `POST /api/admin/simulator/realtime/start`、`/stop`；`GET .../realtime/status` | 实时模拟的启停与状态 |
| `POST /api/admin/analytics/pv/rebuild` | 重算 PV 汇总（可选日期范围） |
| `POST /api/admin/analytics/leaderboard/snapshot` | 重算榜单快照（全部已结束周期，或指定一期） |
| `POST /api/admin/analytics/kol/rebuild` | 重算 KOL 分层（统计日 + 周数，可补历史） |
| `GET/PUT /api/admin/points-config`、`DELETE .../points-config/versions/{id}` | 积分权重：按生效日期版本化，修改只影响之后新产生的事件 |
| `GET/POST /api/admin/playbook`、`PUT/DELETE .../playbook/{id}` | 建议文案库维护 |

## 8. 前端页面结构（Vue3+TS，管理员/运营内部使用）

```
├─ 总览看板（多维PV监控 + 趋势预测）
├─ 积分排行榜（月榜/年榜，进行中/历史）
├─ KOL管理
│   ├─ TOP1000列表（分层筛选、预警红标）
│   └─ 博主详情钻取页
├─ 系统管理
│   ├─ 数据模拟器控制台
│   ├─ 积分权重配置
│   └─ 建议文案库维护
```

> 实现进度（见 [前端看板设计](2026-10-04-frontend-dashboard-design.md)、[KOL 与系统管理页面设计](2026-10-05-kol-and-admin-ui-design.md)）：总览看板、积分排行榜、KOL 管理（名单 + 博主详情）、系统管理（数据模拟器控制台、积分权重配置、建议文案库）全部页面已实现；积分权重按生效日期版本化，改权重只影响之后新产生的事件。

面向对象：仅管理员/运营人员使用的内部BI看板，不做面向全体员工公开的排行榜页面。

> 实现时的取舍（见 [频道驾驶舱设计](2026-10-05-channel-cockpit-design.md)）：新增"频道驾驶舱"页面（`/channels`、`/channels/:id`）：综合频道和领域频道共用"自己入口的分发"口径，领域频道的自主分发单列，并带来源构成、环比、同类基准中位数和自动诊断；目标跟踪并入后续的增长闭环计划。

> 实现时的取舍（见 [竞赛榜设计](2026-10-05-channel-contest-design.md)）：新增"频道竞赛榜"（`/contest`）：领域榜（23 个）和综合榜（5 个）分开计分，健康分 = 各维度在榜内的百分位得分按权重加总（没有数据得 0 分），周榜/月榜、名次变化、进步榜；实时计算，不做快照和权重配置。

> 实现时的取舍（见 [下钻与对比设计](2026-10-05-drilldown-compare-design.md)）：频道 → 账号 → 博主 → 内容逐级下钻（`/channels/:id`、`/accounts/:id`、`/bloggers/:id`、`/contents/:id`，带面包屑），以及 2–5 个同类对象的对比页（`/compare`）；内容侧指标口径与频道驾驶舱共用，博主按作者归属、合著内容重复计数；账号额外有关注者、新增和取关。

> 实现时的取舍（见 [读者侧分析设计](2026-10-05-reader-analytics-design.md)）：新增"读者分析"（`/readers`）：DAU/WAU/MAU 与黏性、新成员留存同期群、激活漏斗、读者分层（创作者/互动者/沉默读者/仅登录/不活跃）和沉默读者名单（展示姓名）；"活跃"指当天有任何行为记录；演示默认员工数提到 200，历史回填的登录改为批量写入。

> 实现时的取舍（见 [增长闭环设计](2026-10-05-growth-loop-design.md)）：新增"增长闭环"（`/growth`）：部门渗透率、热点与潜力内容识别、告警中心（规则生成、幂等重算、确认/关闭）、带基线/目标/进度和 7 天窗口效果趋势的运营任务；表 `growth_alert`、`growth_task` 不设外键，历史回填时一并清空；演示默认规模提到 1000 员工、100 账号、1500 篇内容。

> 实现时的取舍（见 [创作者与用户分析设计](2026-10-05-creator-user-analytics-design.md)）：新增“创作者分析”（`/creators`：总览与留存、排行、成长阶梯、产出集中度）和“用户分析”（`/users`：生命周期与流转矩阵、价值分层、兴趣与跨领域、用户列表；`/users/:id`：用户 360）。全部在请求内现算、不缓存，创作者指窗口内发过帖的员工；没有新表。

> 实现时的取舍（见 [模拟数据校准设计](2026-10-06-simulation-calibration-design.md)）：浏览改为由登录产生（`ViewPlanner`，每个登录日 Poisson(`viewsPerActiveDay`) 次，内容按热度和新鲜度挑），PV 从 9 千提到 26 万，互动占 PV 约 3%；画像比例缩小为 3/2/3/5%，淡出分散并允许回流，关注和账号做成幂律长尾；历史不再有越过结束日的浏览，演示数据的结束日改为今天；清空旧数据改用 JDBC 批量删除，浏览/曝光/搜索/积分用 `ViewBatchWriter` 批量写入。

## 9. 部署与测试

**部署**：本地/演示环境用 `docker-compose` 一键拉起 OpenGauss + Redis + Kafka + Spring Boot + Vue（Nginx托管前端静态资源）；配置通过 `application.yml`+环境变量管理，不硬编码。该 compose 文件本身也作为正式团队的最小可运行参考环境。

> 实现进度（见 [部署设计](2026-10-05-deployment-design.md)）：`docker compose --profile app up -d --build` 一条命令拉起全栈（Nginx 托管前端并反向代理 `/api`，Kafka 使用 `INTERNAL`/`EXTERNAL` 双监听）；`scripts/seed-demo.sh` 灌入演示数据，`scripts/smoke-test.sh` 做冒烟测试；根目录 `README.md` 汇总了运行方式、配置和已知限制。

**测试**：数据模拟器支持固定随机种子，保证结果可重复；积分引擎单元测试覆盖各行为权重计算及"双边计分/多作者均分"边界逻辑；聚合任务用小规模模拟数据验证汇总结果与明细表直接SUM结果一致；不追求前端E2E覆盖，重点保证后端数据链路正确性。

## 10. 明确不做的范围（Out of Scope）

- 不实现内容社区基础功能本身（频道/账号/内容发布/关注关系的产品能力）——假定已由正式系统提供，本项目只模拟其产生的数据。
- 不实现面向全体员工公开的排行榜/个人主页页面——本项目只做管理员/运营内部BI看板。
- 不引入机器学习模型做预测或用户分群（预测用简单同环比+移动平均外推，KOL分层用规则化RFM，不用聚类/训练模型）。
