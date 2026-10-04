# 前端 BI 看板（基础 + 总览 + 排行榜）· 设计方案

- 日期：2026-10-04
- 状态：已实现（实施计划见 [前端 BI 看板](../plans/2026-10-04-frontend-dashboard.md)）
- 前置依赖：后端 [PV 多维度聚合](2026-10-04-pv-analytics-design.md)、[积分排行榜](2026-10-04-leaderboard-design.md)（本阶段只消费这两组接口，并补三个小接口）；前端脚手架（Vue3 + TS + Vite，只有健康检查页）已在 Plan 1 建好。
- 后续：Plan 8 做 KOL 名单、博主详情页、系统管理（模拟器控制台、积分权重配置、建议文案库）。

## 1. 背景与目标

主设计文档第 8 节规定了管理员/运营使用的内部 BI 看板。本阶段交付看板的骨架和最先要用的两个页面：**总览看板**（PV 趋势、环比同比、预测、Top N）和**积分排行榜**（月榜/年榜）。

## 2. 范围

**包含**：
- 前端基础：路由、整体布局、接口封装、图表封装、公共组件、主题样式
- 总览看板、积分排行榜两个页面
- 后端补充的三组小接口（第 3 节）
- 前端纯逻辑的 Vitest 单测

**明确不包含**（第 9 节）：KOL 名单、博主详情、系统管理页（Plan 8）；登录鉴权、移动端、深色模式、前端 E2E、国际化、生产部署。

## 3. 后端补充

前端筛选和选择器需要的数据，现有接口给不了：

| 接口 | 说明 |
|---|---|
| `GET /api/dimensions/channels` | 全部频道 `[{id, name, category}]`，按 id 排序 |
| `GET /api/dimensions/accounts?keyword=&limit=20` | 账号 `[{id, name, type}]`，按名称模糊搜索（不区分大小写，关键字里的 `%`、`_` 按字面匹配），`limit` 范围 1~100，按名称、id 排序 |
| `GET /api/dimensions/employees?keyword=&limit=20` | 员工 `[{id, name, dept}]`，规则同账号 |
| `GET /api/meta/pv-range` | `{from, to}`：`pv_summary_daily` 里有数据的日期范围；没有数据时两个值都为 `null` |
| `GET /api/leaderboard/{month\|year}/periods` | `[{periodValue, status}]`：所有有积分流水的周期，再加上当前周期（即使还没有流水），按时间倒序；`status` 为 `IN_PROGRESS`/`ARCHIVED` |

- 账号和员工数量可能很大，所以用"关键字搜索 + limit"，不一次全拉。
- `pv-range` 是必需的：模拟数据只到某个历史日期，而今天可能晚得多，看板默认范围必须以数据范围为准，否则一打开全是空图。
- 参数非法（`limit` 越界、路径类型不是 month/year）返回 400 文字说明。
- **不需要处理跨域**：开发时用 Vite 代理 `/api`（目标地址由环境变量 `VITE_API_TARGET` 决定，默认 `http://localhost:8080`），上线时由 Nginx 反向代理（属于部署那份计划）。

## 4. 前端技术栈与目录

- 已有：Vue 3、TypeScript、Vite 5。新增：`vue-router`、`element-plus`（按需自动导入，`unplugin-vue-components` + `unplugin-auto-import`，生成的 `components.d.ts`/`auto-imports.d.ts` 提交进仓库以保证 `vue-tsc` 在干净克隆上能通过）、`echarts`（按模块导入）、`vitest@2`（与 Vite 5 兼容）。
- 请求用 `fetch` 封装一层带类型的客户端，不引入 axios；不引入 Pinia，页面数据在页面内管理。

```
src/
  api/        http.ts query.ts types.ts analytics.ts leaderboard.ts health.ts
  utils/      format.ts dateRange.ts kpi.ts trendTable.ts
  charts/     colors.ts trendOption.ts topBarOption.ts leaderboardOption.ts
  composables/useAsync.ts
  components/ BaseChart.vue AsyncBlock.vue RemoteSelect.vue HealthBadge.vue
  views/      OverviewView.vue LeaderboardView.vue
              overview/ PvFilterBar.vue KpiCards.vue TrendPanel.vue TopPanel.vue types.ts
  router/index.ts   styles/theme.css   App.vue   main.ts
```

## 5. 通用能力

- **整体布局**：左侧菜单（总览看板、积分排行榜），顶栏显示页面标题和后端健康状态。KOL 和系统管理页留给 Plan 8，不放灰色占位。
- **统一的加载、空数据、错误状态**（`AsyncBlock`）：请求失败时直接显示后端返回的那句文字（后端的 400/404 响应体就是一句说明原因的文字）；5xx 显示通用提示，不暴露内部信息。
- **`useAsync`**：封装"加载中/数据/错误"，并忽略过期响应（先发后到的旧请求不能覆盖新请求的结果）。
- **`BaseChart`**：按模块注册 ECharts，随容器大小自动缩放，卸载时销毁实例。
- **图表配置全是纯函数**（接口数据进、ECharts 配置出），所以可以单测。

## 6. 总览看板

- **筛选栏**：粒度（日/周/月/年）；日期范围（选择器随粒度切换成日期/月份/年份范围）；频道下拉；账号、博主的搜索式下拉；内容类型下拉（全部/文章/视频）；预测期数（0~12，默认 3）。任一筛选变化即重新查询。
- **默认范围**（以数据范围的最后一天为终点，起点不早于数据范围的第一天）：日 30 天、周 26 周、月 12 个月、年 5 年；切换粒度时重置为该粒度的默认范围。没有数据范围（`pv-range` 为空）时整页显示"暂无 PV 数据，请先在后台回填并重算汇总"。
- **指标卡**：所选周期合计 PV；最近一个已结束周期的 PV 及其环比、同比；下一期预测值及置信区间（没有预测时显示"—"）。
- **趋势图**：实线为实际值；进行中的周期用空心点；预测为虚线（从最后一个已结束周期接出）；置信区间用浅色带；悬浮提示里有环比、同比，进行中和预测周期有明确标注。
- **明细表**：每个周期一行（预测行排在最上面并标"预测"，其余按时间倒序），列为周期、PV、环比、同比、状态（预测/进行中/已结束）；环比同比用颜色区分涨跌，没有则显示"—"。
- **Top N 面板**：标签切换频道/账号/博主/内容类型；数量可选 5/10/20；横向柱状图；沿用上面的筛选和日期范围（Top N 不做周期扩展，按实际日期范围统计）；博主维度附注"合著作品每位作者记完整 PV，各博主 PV 之和会大于全站总数"。

## 7. 积分排行榜

- **控件**：月榜/年榜切换；周期下拉（来自 `periods` 接口，默认第一项即当前周期，进行中的周期标注"（进行中）"）；显示人数（10/20/50）。
- **内容**：状态标签（进行中/已归档；实时/快照）；前 10 名的主动/被动堆叠横向条形图；榜单表（名次、姓名、总分、主动分、被动分、主动被动占比条）。并列的人显示同一名次，前三名名次有醒目的徽标样式。
- 周期没有数据时显示空状态；请求出错时显示后端返回的文字。

## 8. 测试与验证

- **后端**：新接口的集成测试用 2019 年夹具；`periods` 的逻辑用固定 `Clock` 的 Mockito 单测。
- **前端纯逻辑单测（Vitest）**：`buildQuery`、`getJson` 的错误处理、`format`、`dateRange`（默认范围、选择器与日期互转）、`kpi`、`trendTable`、三个图表配置函数（含悬浮提示内容）、`useAsync` 的过期响应处理。
- **构建门槛**：`vue-tsc` 类型检查和 `vite build` 必须通过。
- **人工验证**：启动后端并灌入演示数据（开画像回填、补多周 KOL 重算、重算 PV 汇总、生成榜单快照），再启动 Vite，在内置浏览器里逐页检查并截图：筛选联动、预测虚线与置信带、进行中周期、空状态、错误提示、排行榜周期切换。

## 9. 明确不做的范围（Out of Scope）

- KOL 名单、博主详情页、系统管理页（Plan 8）。
- 登录与鉴权、移动端（主设计文档约定 PC 用 TypeScript、移动端用 JavaScript，移动端不在本项目范围）、深色模式、前端 E2E、国际化。
- 生产部署（Nginx 与 compose 集成，属于部署那份计划）。
