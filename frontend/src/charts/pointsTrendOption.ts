import type { EChartsOption } from "echarts";
import type { PointsTrendSeries } from "../api/types";
import { formatNumber, formatPeriod } from "../utils/format";
import { ACTIVE, PASSIVE } from "./colors";

/** 积分构成趋势：各周期的主动/被动积分堆叠柱状图。 */
export function buildPointsTrendOption(series: PointsTrendSeries): EChartsOption {
  const bar = (name: string, data: number[]) => ({ name, type: "bar" as const, stack: "points", barMaxWidth: 28, data });
  return {
    color: [ACTIVE, PASSIVE],
    grid: { left: 56, right: 24, top: 40, bottom: 36 },
    legend: { data: ["主动积分", "被动积分"], top: 4 },
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    xAxis: { type: "category", data: series.points.map((p) => formatPeriod(p.periodStart, series.granularity)) },
    yAxis: { type: "value", axisLabel: { formatter: (value: number) => formatNumber(value) } },
    series: [
      bar("主动积分", series.points.map((p) => p.activePoints)),
      bar("被动积分", series.points.map((p) => p.passivePoints)),
    ],
  };
}
