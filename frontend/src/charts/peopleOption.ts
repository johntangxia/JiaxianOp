import type { EChartsOption } from "echarts";
import type {
  CreatorConcentrationShare,
  CreatorLadderStage,
  CreatorPoint,
  Granularity,
  UserLifecycleStageRow,
  UserShare,
  UserValueSegmentRow,
  UserWeekActivity,
} from "../api/types";
import { formatPercent } from "../utils/channelMetrics";
import { formatNumber, formatPeriod } from "../utils/format";
import { creatorStageColor, lifecycleColor, sourceLabel, valueColor, weekLabel } from "../utils/people";
import { COMMENTS, CTR_LINE, LOGINS, PASSIVE, POSTS, PRIMARY } from "./colors";

/** 创作者趋势：活跃创作者和新增创作者的柱，人均发帖折线（右轴，没有创作者的周期断开）。 */
export function buildCreatorTrendOption(points: CreatorPoint[], granularity: Granularity): EChartsOption {
  return {
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    legend: { top: 0 },
    grid: { left: 12, right: 12, top: 40, bottom: 8, containLabel: true },
    xAxis: { type: "category", data: points.map((p) => formatPeriod(p.periodStart, granularity)) },
    yAxis: [
      { type: "value", minInterval: 1, axisLabel: { formatter: (value: number) => formatNumber(value) } },
      { type: "value", splitLine: { show: false }, axisLabel: { formatter: (value: number) => value.toFixed(1) } },
    ],
    series: [
      { name: "活跃创作者", type: "bar", barMaxWidth: 24, itemStyle: { color: POSTS }, data: points.map((p) => p.activeCreators) },
      { name: "新增创作者", type: "bar", barMaxWidth: 24, itemStyle: { color: PASSIVE }, data: points.map((p) => p.newCreators) },
      {
        name: "人均发帖",
        type: "line",
        yAxisIndex: 1,
        connectNulls: false,
        symbolSize: 6,
        itemStyle: { color: CTR_LINE },
        lineStyle: { color: CTR_LINE },
        data: points.map((p) => p.postsPerCreator),
      },
    ],
  };
}

/** 成长阶梯：横向柱，从"未激活"到 KOL 自上而下，柱上标人数和占成员比。 */
export function buildLadderOption(stages: CreatorLadderStage[]): EChartsOption {
  return {
    grid: { left: 12, right: 110, top: 8, bottom: 8, containLabel: true },
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    xAxis: { type: "value", minInterval: 1 },
    yAxis: { type: "category", inverse: true, data: stages.map((s) => s.label) },
    series: [
      {
        type: "bar",
        barMaxWidth: 26,
        label: {
          show: true,
          position: "right",
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          formatter: (params: any) => `${stages[params.dataIndex].count}（${formatPercent(stages[params.dataIndex].share, 0)}）`,
        },
        data: stages.map((s) => ({ value: s.count, itemStyle: { color: creatorStageColor(s.stage) } })),
      },
    ],
  };
}

/** 集中度：每档头部创作者的 PV 占比和发帖占比并排的柱（没有数据的占比画成空）。 */
export function buildConcentrationOption(shares: CreatorConcentrationShare[]): EChartsOption {
  return {
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    legend: { top: 0 },
    grid: { left: 12, right: 12, top: 40, bottom: 8, containLabel: true },
    xAxis: { type: "category", data: shares.map((s) => `${s.label}（${s.creators} 人）`) },
    yAxis: { type: "value", max: 1, axisLabel: { formatter: (value: number) => formatPercent(value, 0) } },
    series: [
      {
        name: "PV 占比",
        type: "bar",
        barMaxWidth: 28,
        itemStyle: { color: PRIMARY },
        label: { show: true, position: "top", formatter: (params: { value: unknown }) => formatPercent(params.value as number | null, 0) },
        data: shares.map((s) => s.pvShare),
      },
      {
        name: "发帖占比",
        type: "bar",
        barMaxWidth: 28,
        itemStyle: { color: POSTS },
        label: { show: true, position: "top", formatter: (params: { value: unknown }) => formatPercent(params.value as number | null, 0) },
        data: shares.map((s) => s.postShare),
      },
    ],
  };
}

/** 生命周期：当前与上一个时点各阶段人数并排的柱，每个阶段固定配色（上一时点用半透明）。 */
export function buildLifecycleOption(stages: UserLifecycleStageRow[]): EChartsOption {
  return {
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    legend: { top: 0 },
    grid: { left: 12, right: 12, top: 40, bottom: 8, containLabel: true },
    xAxis: { type: "category", data: stages.map((s) => s.label) },
    yAxis: { type: "value", minInterval: 1 },
    series: [
      {
        name: "上一时点",
        type: "bar",
        barMaxWidth: 26,
        data: stages.map((s) => ({ value: s.previousCount, itemStyle: { color: lifecycleColor(s.stage), opacity: 0.35 } })),
      },
      {
        name: "当前",
        type: "bar",
        barMaxWidth: 26,
        label: { show: true, position: "top" },
        data: stages.map((s) => ({ value: s.count, itemStyle: { color: lifecycleColor(s.stage) } })),
      },
    ],
  };
}

/** 价值分层占比：每层固定配色；人数为 0 的层不画。 */
export function buildValuePieOption(segments: UserValueSegmentRow[]): EChartsOption {
  const visible = segments.filter((s) => s.count > 0);
  return {
    tooltip: {
      trigger: "item",
      formatter: (params: unknown) => {
        const p = params as { name: string; value: number; percent: number };
        return `${p.name}<br/>${formatNumber(p.value)} 人（${p.percent.toFixed(1)}%）`;
      },
    },
    legend: { orient: "vertical", right: 0, top: "middle" },
    series: [
      {
        type: "pie",
        radius: ["45%", "70%"],
        center: ["38%", "50%"],
        label: { show: false },
        data: visible.map((s) => ({ name: s.label, value: s.count, itemStyle: { color: valueColor(s.segment) } })),
      },
    ],
  };
}

/** 用户 360 的活跃时间轴：每周的登录、浏览、互动、发帖次数堆叠柱。 */
export function buildTimelineOption(weeks: UserWeekActivity[]): EChartsOption {
  const bar = (name: string, color: string, pick: (w: UserWeekActivity) => number) => ({
    name,
    type: "bar" as const,
    stack: "activity",
    barMaxWidth: 22,
    itemStyle: { color },
    data: weeks.map(pick),
  });
  return {
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    legend: { top: 0 },
    grid: { left: 12, right: 12, top: 40, bottom: 8, containLabel: true },
    xAxis: { type: "category", data: weeks.map((w) => weekLabel(w.weekStart)) },
    yAxis: { type: "value", minInterval: 1 },
    series: [
      bar("登录", LOGINS, (w) => w.logins),
      bar("浏览", PASSIVE, (w) => w.views),
      bar("互动", COMMENTS, (w) => w.interactions),
      bar("发帖", POSTS, (w) => w.posts),
    ],
  };
}

const SOURCE_COLORS: Record<string, string> = { ENTRY: "#2f6fed", SEARCH: "#14a38b", SHARE: "#f08c2c", OTHER: "#9aa3b2" };

/** 用户的浏览来源构成；次数为 0 的来源不画。 */
export function buildSourcePieOption(sources: UserShare[]): EChartsOption {
  const visible = sources.filter((s) => s.count > 0);
  return {
    tooltip: {
      trigger: "item",
      formatter: (params: unknown) => {
        const p = params as { name: string; value: number; percent: number };
        return `${p.name}<br/>${formatNumber(p.value)} 次（${p.percent.toFixed(1)}%）`;
      },
    },
    legend: { orient: "vertical", right: 0, top: "middle" },
    series: [
      {
        type: "pie",
        radius: ["45%", "70%"],
        center: ["38%", "50%"],
        label: { show: false },
        data: visible.map((s) => ({ name: sourceLabel(s.label), value: s.count, itemStyle: { color: SOURCE_COLORS[s.label] ?? "#9aa3b2" } })),
      },
    ],
  };
}
