import type { EChartsOption } from "echarts";
import type { Granularity, ReaderActivityPoint, ReaderFunnelStep, ReaderSegmentCount } from "../api/types";
import { formatPercent } from "../utils/channelMetrics";
import { formatNumber, formatPeriod } from "../utils/format";
import { roleColor } from "../utils/readers";
import { CTR_LINE, PASSIVE, PRIMARY } from "./colors";

/** 活跃趋势：活跃人数（日 = DAU、周 = WAU、月 = MAU）和新增成员的柱，活跃率折线（右轴，没有成员的周期断开）。 */
export function buildReaderActivityOption(points: ReaderActivityPoint[], granularity: Granularity): EChartsOption {
  return {
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    legend: { top: 0 },
    grid: { left: 12, right: 12, top: 40, bottom: 8, containLabel: true },
    xAxis: { type: "category", data: points.map((p) => formatPeriod(p.periodStart, granularity)) },
    yAxis: [
      { type: "value", axisLabel: { formatter: (value: number) => formatNumber(value) } },
      { type: "value", axisLabel: { formatter: (value: number) => formatPercent(value, 0) }, splitLine: { show: false } },
    ],
    series: [
      { name: "活跃人数", type: "bar", barMaxWidth: 24, itemStyle: { color: PRIMARY }, data: points.map((p) => p.activeUsers) },
      { name: "新增成员", type: "bar", barMaxWidth: 24, itemStyle: { color: PASSIVE }, data: points.map((p) => p.newMembers) },
      {
        name: "活跃率",
        type: "line",
        yAxisIndex: 1,
        connectNulls: false,
        symbolSize: 6,
        itemStyle: { color: CTR_LINE },
        lineStyle: { color: CTR_LINE },
        data: points.map((p) => p.activeRate),
      },
    ],
  };
}

/** 激活漏斗：横向柱，第一步（加入）在最上面，柱上标人数和占加入人数的比例。 */
export function buildFunnelOption(steps: ReaderFunnelStep[]): EChartsOption {
  return {
    grid: { left: 12, right: 110, top: 8, bottom: 8, containLabel: true },
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    xAxis: { type: "value", minInterval: 1 },
    yAxis: { type: "category", inverse: true, data: steps.map((s) => s.label) },
    series: [
      {
        type: "bar",
        barMaxWidth: 26,
        itemStyle: { color: PRIMARY },
        label: {
          show: true,
          position: "right",
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          formatter: (params: any) => {
            const step = steps[params.dataIndex];
            return `${step.count}（${formatPercent(step.rateOfJoined, 0)}）`;
          },
        },
        data: steps.map((s) => s.count),
      },
    ],
  };
}

/** 读者分层占比：每类固定配色；人数为 0 的类不画。 */
export function buildSegmentPieOption(segments: ReaderSegmentCount[]): EChartsOption {
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
        data: visible.map((s) => ({ name: s.label, value: s.count, itemStyle: { color: roleColor(s.role) } })),
      },
    ],
  };
}
