# 增长闭环（热点内容、部门渗透、告警、运营任务与效果追踪）· 设计方案

- 日期：2026-10-05
- 状态：待实施（实施计划见 [增长闭环](../plans/2026-10-05-growth-loop.md)）
- 前置依赖：前面所有计划（频道驾驶舱的诊断、下钻指标、读者侧分析、KOL 流失预警）已实现。这是"运营分析增强"路线图的最后一份。

## 1. 目标

把前面的"看"变成"做"：发现该关注的内容、部门和频道（热点内容、部门渗透、告警），建立运营任务并跟踪动作有没有起效（任务效果追踪，含原计划里的目标跟踪）。

## 2. 统一的"现在"和窗口

统计日 `asOf` 默认取最近一次登录/发帖的日期（和 KOL、读者分析同一口径），没有数据时取今天；所有接口都可以显式传 `asOf`。窗口都往前推、含 `asOf` 当天。

## 3. 部门渗透率 `GET /api/growth/departments?asOf&days=30`

部门就是 23 个领域（`employee.dept`）。窗口 `[asOf − days + 1, asOf]`（`days` 取 7–90，默认 30），对比上一个等长窗口。每个部门：

| 指标 | 含义 |
|---|---|
| 成员数 | 窗口结束日已是成员的员工 |
| 活跃成员数 | 窗口内有过任何行为记录 |
| **渗透率** | 活跃成员 / 成员数 |
| 创作者 / 互动者 / 沉默读者 | 同读者分析的角色判定 |
| 新增成员 | 窗口内加入 |
| 上期成员数、活跃成员数、渗透率 | 用来看变化 |

外加全社区合计一行。成员为 0 的部门渗透率为空。

## 4. 热点内容识别 `GET /api/growth/hot-contents?asOf&limit=20`

- **热点**：近 3 天 PV ≥ 10 且 ≥ 同频道"有浏览内容的 3 天 PV 中位数"的 3 倍（中位数至少按 1 算）。给出 3 天 PV、热度倍数、相对前 3 天的增长倍数（前 3 天按至少 1 算）、互动率、读完率。按热度倍数从高到低。
- **潜力内容**：近 30 天 PV ≥ 5，PV 不超过同频道（30 天内有浏览的内容）PV 中位数，且互动率或读完率达到同频道中位数的 1.5 倍以上。按"质量倍数"（互动率倍数与读完率倍数的较大者）从高到低。适合"再推一把"。
- 每条带内容编号、类型、频道、账号、发布时间，前端可进内容页或创建任务。

## 5. 告警

### 5.1 数据
表 `growth_alert`：`id`、`kind`、`severity`（CRITICAL/WARN）、`entity_type`（CHANNEL/DEPARTMENT/OVERALL/BLOGGER）、`entity_id`（可空）、`entity_name`、`period_end`、`message`、`status`（OPEN/ACKED/CLOSED）、`created_at`、`updated_at`。唯一键 `(kind, entity_type, entity_name, period_end)`——同一对象同一统计日的同类告警只有一条。**不设外键**，历史回填会清空告警和任务（它们指向的模拟对象都被重建了）。

### 5.2 规则（阈值是常量）
| kind | 对象 | 触发条件 | 级别 |
|---|---|---|---|
| `CHANNEL_<诊断代码>` | 频道 | 频道驾驶舱（窗口 30 天）诊断里的严重/警告级别，说明沿用诊断原文 | 同诊断 |
| `OVERALL_DAU_DROP` | 全社区 | 近 7 天日均 DAU 比前 7 天下降超过 20%（前 7 天日均 ≥ 5）；超过 50% 为严重 | WARN/CRITICAL |
| `OVERALL_LOW_ACTIVATION` | 全社区 | 在 `[asOf−44, asOf−14]` 加入的新成员（至少 10 人）里，第 2 周仍活跃的不到 30% | WARN |
| `DEPARTMENT_LOW_PENETRATION` | 部门 | 渗透率低于各部门中位数的一半，且成员 ≥ 20 | WARN |
| `BLOGGER_CHURN_RISK` | 博主 | 最近一次 KOL 重算里被判为流失预警的博主，最多取排名最靠前的 30 个 | WARN |

### 5.3 接口
- `POST /api/admin/growth/alerts/rebuild?asOf=`：按规则生成告警，**幂等**：不存在则新建（OPEN），已存在且未关闭则更新严重程度和说明（保留状态），已关闭的不重开。返回新建数、更新数、总数。
- `GET /api/growth/alerts?status&kind&entityType&limit&offset`：分页（`limit` 1–100，默认 20），严重程度优先、再按创建时间倒序；同时返回各状态的数量。
- `PUT /api/admin/growth/alerts/{id}/status`，body `{"status": "ACKED"}`：改状态；不存在 404，状态非法 400。

## 6. 运营任务与效果追踪

### 6.1 数据
表 `growth_task`：`id`、`title`、`task_type`（PUSH_HOT_CONTENT / IMPROVE_OWN_ENTRY / RECALL_LURKERS / ONBOARD_NEWCOMERS / ENCOURAGE_POSTING / OTHER）、`entity_type`（CHANNEL/ACCOUNT/BLOGGER/DEPARTMENT/OVERALL）、`entity_id`、`entity_name`、`metric`、`baseline_value`、`target_value`、`owner`（自由文本，没有账号体系）、`start_date`、`due_date`、`status`（TODO/DOING/DONE/CANCELLED）、`note`、`alert_id`、`created_at`、`updated_at`。

### 6.2 指标
| 指标 | 适用对象 | 含义 | 方向 |
|---|---|---|---|
| `PV` | 频道、账号、博主 | 窗口内内容 PV | 越大越好 |
| `OWN_ENTRY_SHARE` | 频道 | 自主分发占比 | 越大越好 |
| `OWN_CTR` | 频道 | 自主入口点击率 | 越大越好 |
| `INTERACTION_RATE` | 频道、账号、博主 | 互动率 | 越大越好 |
| `POSTS` | 频道、账号、博主 | 窗口内发布数 | 越大越好 |
| `PENETRATION` | 部门 | 渗透率 | 越大越好 |
| `ACTIVE_USERS` | 部门、全社区 | 窗口内活跃人数 | 越大越好 |
| `ACTIVE_RATE` | 全社区 | 窗口内活跃人数 / 成员数 | 越大越好 |

指标和对象类型不匹配时创建返回 400。指标值没有数据（比如没有 PV 时的占比）为空。

### 6.3 基线、当前值、进度
- **窗口**：默认 30 天。**基线** = 开始日前一天往前 30 天的指标值（创建时算好并存下）；**当前值** = 以 `asOf` 为终点的最近 30 天的指标值。
- **进度** = (当前值 − 基线) / (目标值 − 基线)；目标和基线相等或任一为空时为空；允许超过 100% 或为负。
- **趋势**：以 `asOf` 为终点、每 7 天一个窗口（7 天窗口的指标值）的最近 8 个点，并标出开始日所在的那一点。

### 6.4 接口
- `POST /api/admin/growth/tasks`：创建，返回任务。`title`、`entityType`、`metric`、`targetValue` 必填；`entityType` 为频道/账号/博主时 `entityId` 必填且对象必须存在，部门用 `entityName` 且必须是已有部门，全社区不需要对象；`startDate` 默认 `asOf`，`dueDate` 不能早于 `startDate`；`alertId` 可选，必须存在。
- `GET /api/growth/tasks?status&limit&offset`：分页（同上），最新的在前，每行带当前值和进度。
- `GET /api/growth/tasks/{id}?asOf`：任务详情，带基线、目标、当前值、进度和趋势。
- `PUT /api/admin/growth/tasks/{id}`：可改 `title`、`owner`、`status`、`targetValue`、`dueDate`、`note`；对象、指标和基线不可改。
- `DELETE /api/admin/growth/tasks/{id}`。

## 7. 页面 `/growth`"增长闭环"

四个标签页：
- **告警中心**：按状态（默认待处理）和类型筛选的表，每行"确认""关闭""创建任务"；顶部有"重算告警"。
- **运营任务**：按状态筛选的表（对象、指标、基线 → 当前 → 目标、进度条、负责人、到期日），"新建任务"对话框；点一行打开详情抽屉，里面有效果趋势图和状态/负责人/备注的修改。
- **热点内容**：热点表和潜力内容表，每行"看内容""创建任务"。
- **部门渗透**：部门表带渗透率条和上期对比，点一行进入对应领域频道。

从告警、热点内容或部门创建任务时，对话框按类型预填标题、任务类型、对象和指标，目标值由人填写。

## 8. 后端结构
新增 `growth` 包：纯函数（`DepartmentPenetrationBuilder`、`HotContentDetector`、`AlertRules`、`TaskProgress`）、`GrowthQueryRepository`、`GrowthAlert`/`GrowthTask` 实体和仓库、`MetricEvaluator`（按对象类型从已有的驾驶舱、下钻、读者仓库取指标，请求内缓存窗口结果）、`GrowthService`、`GrowthController`（+ 异常处理）。表走 `schema.sql`。

## 9. 演示数据规模
演示默认规模提到 1000 员工、100 账号、1500 篇内容（`seed-demo.sh` 和控制台默认值）：部门渗透率和热点识别需要足够的人数和内容量；回填约 1 分钟。

## 10. 测试
纯函数（渗透率、热点/潜力判定、各告警规则、进度计算）、仓库（2019 夹具）、服务与接口（幂等重算、状态流转、校验、效果计算）、端到端（回填后部门成员数之和 = 成员总数、重算告警两次数量不变、任务创建后基线与手算一致）、前端工具函数和图表配置、浏览器走查。

## 11. 明确不做的范围（Out of Scope）
- 邮件、企业微信、Webhook 等外部通知。
- 任务的评论、协作、审批；告警阈值在线配置；告警自动关闭。
- 任务对象、指标、基线的修改（改了就应该新建任务）。
