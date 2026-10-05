import type { EChartsOption } from "echarts";
import type { CompareSubject, Granularity } from "../api/types";
import { formatNumber, formatPeriod } from "../utils/format";
import { formatPercent } from "../utils/channelMetrics";
import {
  PRIMARY,
  SOURCE_COMPOSITE_ENTRY,
  SOURCE_OTHER,
  SOURCE_OWN_ENTRY,
  SOURCE_SEARCH,
  SOURCE_SHARE,
} from "./colors";

const LINE_COLORS = [PRIMARY, "#f08c2c", "#14a38b", "#8b5cf6", "#d9363e"];

function totalPv(point: CompareSubject["trend"][number]): number {
  return point.ownEntry + point.compositeEntry + point.search + point.share + point.other;
}

/** PV 趋势叠加：每个对象一条折线，横轴是第一个对象的周期（所有对象的周期相同）。 */
export function buildCompareTrendOption(subjects: CompareSubject[], granularity: Granularity): EChartsOption {
  const labels = (subjects[0]?.trend ?? []).map((p) => formatPeriod(p.periodStart, granularity));
  return {
    tooltip: { trigger: "axis" },
    legend: { top: 0 },
    grid: { left: 12, right: 12, top: 40, bottom: 8, containLabel: true },
    xAxis: { type: "category", data: labels },
    yAxis: { type: "value", axisLabel: { formatter: (value: number) => formatNumber(value) } },
    series: subjects.map((subject, i) => ({
      name: subject.name,
      type: "line" as const,
      symbolSize: 6,
      itemStyle: { color: LINE_COLORS[i % LINE_COLORS.length] },
      lineStyle: { color: LINE_COLORS[i % LINE_COLORS.length] },
      data: subject.trend.map(totalPv),
    })),
  };
}

/** 来源构成对比：每个对象一根横向的 100% 堆叠柱，没有 PV 的对象全部为 0。 */
export function buildCompareMixOption(subjects: CompareSubject[]): EChartsOption {
  const share = (subject: CompareSubject, pick: (m: CompareSubject["current"]["content"]["sourceMix"]) => number): number => {
    const content = subject.current.content;
    return content.pv === 0 ? 0 : pick(content.sourceMix) / content.pv;
  };
  const bar = (name: string, color: string, pick: Parameters<typeof share>[1]) => ({
    name,
    type: "bar" as const,
    stack: "mix",
    barMaxWidth: 28,
    itemStyle: { color },
    data: subjects.map((subject) => share(subject, pick)),
  });
  return {
    tooltip: {
      trigger: "axis",
      axisPointer: { type: "shadow" },
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      valueFormatter: (value: any) => formatPercent(Number(value)),
    },
    legend: { top: 0 },
    grid: { left: 12, right: 24, top: 40, bottom: 8, containLabel: true },
    xAxis: { type: "value", max: 1, axisLabel: { formatter: (value: number) => formatPercent(value, 0) } },
    yAxis: { type: "category", inverse: true, data: subjects.map((s) => s.name) },
    series: [
      bar("自主入口", SOURCE_OWN_ENTRY, (m) => m.ownEntry),
      bar("综合入口", SOURCE_COMPOSITE_ENTRY, (m) => m.compositeEntry),
      bar("搜索", SOURCE_SEARCH, (m) => m.search),
      bar("分享", SOURCE_SHARE, (m) => m.share),
      bar("其他", SOURCE_OTHER, (m) => m.other),
    ],
  };
}
