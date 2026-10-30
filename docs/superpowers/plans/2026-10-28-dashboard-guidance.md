# 看板说明 Implementation Plan

> **For agentic workers:** 执行时使用 `superpowers:executing-plans`，按下面的复选框逐项验证；执行结果以工作区和验证记录为准。

**Goal:** 为全部分析看板、详情页和系统管理页提供可访问的说明入口，解释含义、计算口径、阅读方法、分析用途和必要限制。

**Architecture:** 前端使用统一的右侧说明抽屉和可复用的页面、指标入口；说明内容按业务域组织，指标口径以稳定 ID 复用，口径不同则使用不同 ID。首批接入频道详情和用户分析，随后覆盖其余页面；动态例子只使用当前已返回且口径一致的数据，并在数据不足时回退至固定说明。

**Tech Stack:** 现有 Vue 3、TypeScript、Element Plus、Vue Router、Vitest、Vite；默认不引入新依赖或后端接口。

**Spec:** 本计划的「产品与口径约定」记录本次会话提出的方案；项目背景和操作边界见 `README.md`、`AGENTS.md`，各业务口径以对应后端实现和 `docs/superpowers/specs/` 为依据。旧设计与现有代码冲突时先核实实际行为并记录差异。

## 产品与口径约定

- 每个页面有「看板说明」入口；有标签页的页面根据当前标签打开对应说明，详情页按实际对象类型显示适用内容。
- 复杂卡片、图表、表格列或图表整体有独立说明入口；说明入口不触发原有排序、行跳转、图表点击或筛选行为。
- 右侧抽屉包含「含义」「怎么算」「怎么看 / 怎么分析」「注意事项」；示意例子明确标为示意，当前数据例子显示对象和时间范围。
- 保留仍准确且有助于扫读的页面短提示；删除或缩短与新说明重复的长文时不得丢失必要口径。
- 内容须说明统计对象、时间窗口、分子分母、去重、空值和适用的合著规则；指出模拟数据、经验阈值或结论限制。不要把行为标签或相对评分解释为真实价值或因果结论。
- 不写死可变权重、KOL 名单人数、日期或页面数据；第一版提示到设置位置查看配置。无可用数据时仅展示固定说明和明确标注的示意例子。
- KOL RFM 与全员价值 RFM、频道入口曝光/点击率与内容曝光/点击率分别建 ID；页面不能错用口径。
- 说明以文本渲染，不使用未经处理的 HTML；支持中文、键盘、可见焦点、触屏、抽屉滚动及窄屏布局。
- 现有工作区有未提交修改，实施前记录并保留；不清空数据库、不运行演示回填、不推送或提交已有改动。
- 本计划提出后已获用户授权实施；提交和推送需结合后续用户要求处理。

## 文件与接口约定

- `frontend/src/guidance/types.ts`：定义 `GuidanceId`、`GuidanceEntry`、`GuidanceContext`。条目包含 ID、标题、简短定义、四个说明段落和内部核对来源；来源供维护，不显示源码路径给最终用户。
- `frontend/src/guidance/catalog.ts`：导出 `getGuidance(id: GuidanceId): GuidanceEntry` 和页面、标签映射；按业务域拆为 `guidance/entries/*.ts`，避免单文件过大。未知 ID 在开发和测试时明确报错，不展示错误口径。
- `frontend/src/guidance/context.ts`：定义 Vue `InjectionKey` 和 `GuidanceController`：`open(id: GuidanceId, context?: GuidanceContext): void`、`close(): void`；将打开时的触发元素和当前上下文保存在控制器内。
- `frontend/src/guidance/examples.ts`：`ratioExample(input: RatioExampleInput): string | null`，接收名称、日期范围、分子、分母和单位；仅在数值有限、分母大于 0 且上下文匹配时返回计算示例。
- `frontend/src/components/GuidanceProvider.vue` 和 `GuidanceDrawer.vue`：在 `App.vue` 的 `el-config-provider` 内提供控制器并渲染唯一抽屉；打开时聚焦标题或关闭按钮，关闭后返回触发入口，切换路由时关闭并清空上下文。
- `frontend/src/components/GuidanceButton.vue`：`id: GuidanceId`、`label: string`、可选 `context: GuidanceContext`；页面入口显示「看板说明」，指标入口显示 `ⓘ`，可访问名称为「查看{label}说明」，点击阻止冒泡。
- `frontend/src/guidance/*.test.ts`：用现有 Vitest 的 Node 环境测试内容映射、口径差异、动态示例与状态逻辑；抽屉焦点和 Element Plus 行为以真实浏览器验证。

## Review Focus

1. 同名指标在入口、内容和两种 RFM 中口径不同：Task 1 测试 ID 唯一及对应定义；Task 3、4 浏览器核对实际入口。
2. 切换筛选、标签或路由后旧说明或例子残留：Task 2 测试状态转换，Task 3、5 浏览器复核。
3. 空值、零分母、加载中或对象不匹配时产生虚假当前数据：Task 6 对例子函数和浏览器场景验证回退。
4. 表头或表格行中的说明按钮误触发排序或下钻：Task 3、4 的浏览器操作覆盖。
5. 仅鼠标可访问或关闭后焦点丢失：Task 2、7 用键盘、窄屏和屏幕阅读器名称检查。

## Task 1：建立口径库和覆盖清单

**文件：**创建 `frontend/src/guidance/types.ts`、`catalog.ts`、`entries/channels.ts`、`entries/users.ts`、`catalog.test.ts`。其他业务域内容在 Task 4 按相同结构新增。

**接口：**产出 `GuidanceEntry`、`GuidanceId`、`getGuidance(id)` 和 `getPageGuidance(routeName, tab?)`。页面映射只接受明确注册的页面/标签组合；未注册时返回 `null`，不回退到别的页面说明。

- [ ] 先写 Vitest：同一 ID 唯一；频道入口点击率和内容点击率指向不同定义；KOL RFM 与用户 RFM 指向不同定义；未知页面返回 `null`。
- [ ] 运行 `cd frontend && npx vitest run src/guidance/catalog.test.ts`，确认新增测试因接口不存在而失败。
- [ ] 实现类型、目录和首批文案。频道至少覆盖入口曝光/点击率、内容点击率、互动率、自主分发占比、综合依赖度、头部集中度；用户覆盖生命周期、流转矩阵、价值分层 RFM。每条逐项对照后端查询、日期边界和现有测试；将核对来源记录在条目内。
- [ ] 重跑上述测试并执行 `npm run build`，均成功后检查说明无硬编码的实时数值、可变权重或未经核实的结论。

## Task 2：可复用抽屉和入口

**文件：**创建 `frontend/src/guidance/context.ts`、`context.test.ts`、`frontend/src/components/GuidanceProvider.vue`、`GuidanceDrawer.vue`、`GuidanceButton.vue`；修改 `frontend/src/App.vue`。

**接口：**消费 Task 1 的 ID 和条目；提供 `open(id, context?)`、`close()`；`GuidanceButton` 使用固定 ID 和可选上下文触发。

- [ ] 先写状态测试：打开指定条目；再次打开替换条目和上下文；关闭及路由切换清空上下文；未注册条目不会误显示其他内容。运行测试，确认先失败。
- [ ] 实现抽屉、入口与状态控制；抽屉只渲染文本，桌面宽度适中、窄屏不溢出，关闭后恢复焦点；路由切换自动关闭。页签切换时由所在页面更新入口 ID，已打开的页面说明同步到新标签；具体指标说明在标签切换时关闭。
- [ ] 运行状态测试及 `npm run build`；用本地浏览器验证点击、Enter/Space、Escape、焦点返回、触屏宽度、抽屉滚动与路由切换。

## Task 3：首批试点——频道详情与用户分析

**文件：**修改 `frontend/src/views/channels/ChannelCockpitView.vue`、`ChannelDetailView.vue`、`DistributionCompare.vue`、`CompositeTable.vue`、`DomainTable.vue`、`frontend/src/views/drill/BreakdownTable.vue`；修改 `frontend/src/views/users/UsersView.vue`、`LifecycleTab.vue`、`ValueTab.vue`、`InterestsTab.vue`、`UserListTab.vue`。

**接口：**使用 Task 1 的页面/指标 ID 和 Task 2 的 `GuidanceButton`；首批只展示固定口径与标注为示意的例子。

- [ ] 频道驾驶舱和频道详情提供页面入口；综合频道与领域频道按实际类型区分说明。关键卡片及表头接入 Task 1 的指标 ID；账号表的「内容点击率」使用内容口径。
- [ ] 用户分析四个标签各自接入页面说明，生命周期、流转矩阵、价值分层、兴趣与用户列表提供对应说明入口；切换标签时说明与标签同步。
- [ ] 浏览器走查领域频道、综合频道、账号表和四个用户标签；验证行跳转、表头排序、筛选不受说明按钮影响，空数据和加载时仍能打开适用说明。
- [ ] `cd frontend && npm test && npm run build` 全部通过，记录入口清单和发现的口径差异。

## Task 4：覆盖剩余看板、详情和系统管理

**文件：**新增 `frontend/src/guidance/entries/overview.ts`、`contest.ts`、`drill.ts`、`readers.ts`、`creators.ts`、`growth.ts`、`leaderboard.ts`、`kol.ts`、`admin.ts`；修改下列页面及需要指标入口的子组件。

**覆盖清单：**

| 范围 | 页面和必讲主题 |
|---|---|
| 总览 | `OverviewView.vue`、`overview/`：PV/UV、日期和筛选、环比/同比、预测和置信区间 |
| 竞赛与对比 | `contest/ContestView.vue`、`drill/CompareView.vue`：两类榜、百分位健康分和权重、进步榜、最优规则、综合依赖度、头部集中度 |
| 读者 | `readers/ReadersView.vue` 及四个标签：活跃、留存同期群、各步独立的激活漏斗、读者分层与沉默读者 |
| 创作者 | `creators/CreatorsView.vue` 及四个标签：排行、读完/播完、成长阶梯、到达率与中位耗时、基尼系数、头部占比与合著 |
| 详情 | `drill/AccountView.vue`、`BloggerView.vue`、`ContentView.vue`、`users/UserDetailView.vue`：对象归属、各入口内容曝光、作者合著、用户 360 |
| 增长 | `growth/GrowthView.vue` 及四个标签：告警来源和阈值、任务基线与效果、热点和潜力、部门渗透 |
| 积分与 KOL | `LeaderboardView.vue`、`kol/KolListView.vue`、`KolDetailView.vue`：主动/被动、周期/快照、合著积分分配、名单范围、RFM、六类分层、预警标记与分层区别 |
| 系统管理 | `admin/SimulatorView.vue`、`PointsConfigView.vue`、`PlaybookView.vue`：回填与实时模拟、权重版本、重算和文案匹配；回填/恢复的覆盖影响写清楚 |

- [ ] 扩展 `catalog.test.ts`，逐项列出以上路由和各标签 ID；断言每一入口有非空标题、定义、计算、分析、注意事项和内部来源；断言不同口径没有错误共用 ID。运行并确认新覆盖测试先失败。
- [ ] 对照各领域当前 `Controller`、`Service`、查询代码及对应设计文档填写说明，再接入页面和相关复杂图表/表头；现有的有效短提示保留或合并。配置项只说明其含义和查看位置。
- [ ] 对表格行和图表点击验证 `ⓘ` 独立操作；对每条路由及全部标签打开页面说明，检查默认/空数据、无权限或请求失败时仍能读到固定口径。
- [ ] 执行 `npm test` 和 `npm run build`；逐条核对清单并记录完成情况，任何未覆盖入口留为未完成，不用其他页面的泛化说明冒充。

## Task 5：可访问性与交互回归

**文件：**按 Task 2–4 发现的问题修改相关组件和页面；不引入无关样式改动。

- [ ] 键盘验证每个说明按钮有可识别名称和焦点，Enter/Space 可打开；Escape、关闭按钮和遮罩按 Element Plus 支持行为关闭，焦点返回原按钮。
- [ ] 浏览器验证桌面与 375px 宽度：抽屉可滚动、标题和关闭操作可见、正文不裁切；页面数据、日期与选中对象不因打开说明而改变。
- [ ] 验证页面说明随标签切换更新、指标说明在切换标签时关闭，路由变化不遗留旧内容；表头按钮不排序、行内按钮不下钻。
- [ ] 若发现交互缺陷，修复后重跑对应状态测试和 `npm run build`，并复测该缺陷。

## Task 6：当前数据计算示例

**文件：**创建 `frontend/src/guidance/examples.ts`、`examples.test.ts`；修改频道详情和 `drill/BreakdownTable.vue` 的上下文传递。

**接口：**`RatioExampleInput` 明确含 `subject`、`from`、`to`、`numerator`、`denominator`、`numeratorLabel`、`denominatorLabel`；`ratioExample` 只生成次数比例，格式沿用现有百分比工具。上下文由页面当前响应提供，不自行从显示的百分比反推。

- [ ] 先写测试：142/4909 显示约 2.9%；120/2085 显示约 5.8%；分母为 0、数值缺失/非有限或对象/日期不匹配时返回 `null`；运行确认失败。
- [ ] 在领域频道详情的互动率、账号行的内容点击率接入当前数据例子，展示对象和完整日期范围；无数据时显示固定口径与明确标注的示意例子。账号行通过具名按钮打开，不以首行或上一次选择作为默认对象。
- [ ] 筛选条件或对象改变时，从最新响应重新构建上下文；加载中、失败或来源和当前条件不一致时隐藏旧例子，完成加载后再更新。无需新增后端接口。
- [ ] 运行 `npx vitest run src/guidance/examples.test.ts`、`npm test`、`npm run build`；浏览器验证上述两个例子和切换日期、切换对象、零分母及请求失败场景。

## Task 7：整体验收与维护说明

**文件：**修改 `README.md`，增加「看板说明」入口、内容维护位置和口径变更时的核对流程；检查 `frontend/src/guidance/` 所有条目。

- [ ] 按 Task 4 清单逐页验收：每个页面/标签能打开对应说明；所有讨论过的指标有准确的定义、公式或规则、分析建议与局限；固定例子标示为示意。
- [ ] 复核关键事实：RFM 两套口径、KOL 预警和标签区别、积分合著整数平分、读完率缺失值、同期群和漏斗的窗口、基尼系数人群和合著；核对可变配置未写成当前保证值。
- [ ] 在已有演示服务上运行浏览器抽查，不运行清空或回填；在 `frontend/` 执行 `npm test`、`npm run build`，仓库根目录执行 `git diff --check`。
- [ ] 汇报变更范围、实际通过的测试、未覆盖项目、动态例子的适用范围和工作区状态；保留既有未提交改动。

## 实施记录（2026-10-28）

- 已接入统一说明抽屉、页面和指标入口，并提供频道互动率、频道账号内容点击率的当前数据示例。说明状态由 `App.vue` 提供，抽屉和按钮通过注入使用；未单独增加计划中提到的 `GuidanceProvider.vue`。
- 已用浏览器核对主要路由、四组分析页面的 16 个标签、频道和账号的两个实际计算示例，以及 Escape 关闭、焦点返回和标签切换。前端测试 165 项通过，生产构建通过；本地 8090 前端已更新，健康接口返回 OK。
- 尚未实际在 375px 宽视口或屏幕阅读器中验证，也未逐一模拟所有页面的数据请求失败；抽屉使用不超过视口宽度的布局，并保留上述场景供后续专项验收。
