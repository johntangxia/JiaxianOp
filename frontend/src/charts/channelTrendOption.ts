import type { EChartsOption } from "echarts";
import type { Granularity, TrendPoint } from "../api/types";
import { formatNumber, formatPeriod } from "../utils/format";
import { formatPercent } from "../utils/channelMetrics";
import {
  CTR_LINE,
  PRIMARY,
  SOURCE_COMPOSITE_ENTRY,
  SOURCE_OTHER,
  SOURCE_OWN_ENTRY,
  SOURCE_SEARCH,
  SOURCE_SHARE,
} from "./colors";

/**
 * 频道趋势图。领域频道：按来源堆叠的 PV 柱 + 自主入口点击率折线（右轴）；
 * 综合频道：曝光柱、点击柱 + 点击率折线（右轴）。点击率缺失（没有曝光）的周期断开，不画成 0。
 */
export function buildChannelTrendOption(trend: TrendPoint[], granularity: Granularity, composite: boolean): EChartsOption {
  const labels = trend.map((p) => formatPeriod(p.periodStart, granularity));
  const bar = (name: string, color: string, pick: (p: TrendPoint) => number, stack?: string) => ({
    name,
    type: "bar" as const,
    stack,
    barMaxWidth: 28,
    itemStyle: { color },
    data: trend.map(pick),
  });

  const series = composite
    ? [bar("曝光", SOURCE_SEARCH, (p) => p.impressions), bar("点击", PRIMARY, (p) => p.clicks)]
    : [
        bar("自主入口", SOURCE_OWN_ENTRY, (p) => p.ownEntry, "pv"),
        bar("综合入口", SOURCE_COMPOSITE_ENTRY, (p) => p.compositeEntry, "pv"),
        bar("搜索", SOURCE_SEARCH, (p) => p.search, "pv"),
        bar("分享", SOURCE_SHARE, (p) => p.share, "pv"),
        bar("其他", SOURCE_OTHER, (p) => p.other, "pv"),
      ];

  return {
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    legend: { top: 0 },
    grid: { left: 12, right: 12, top: 40, bottom: 8, containLabel: true },
    xAxis: { type: "category", data: labels },
    yAxis: [
      { type: "value", axisLabel: { formatter: (value: number) => formatNumber(value) } },
      { type: "value", axisLabel: { formatter: (value: number) => formatPercent(value, 0) }, splitLine: { show: false } },
    ],
    series: [
      ...series,
      {
        name: composite ? "点击率" : "自主入口点击率",
        type: "line",
        yAxisIndex: 1,
        connectNulls: false,
        symbolSize: 6,
        itemStyle: { color: CTR_LINE },
        lineStyle: { color: CTR_LINE },
        data: trend.map((p) => p.ctr),
      },
    ],
  };
}
