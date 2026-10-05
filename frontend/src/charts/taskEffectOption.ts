import type { EChartsOption } from "echarts";
import type { GrowthMetric, GrowthTaskDetail } from "../api/types";
import { formatMetricValue, isPercentMetric } from "../utils/growth";
import { FORECAST, PRIMARY } from "./colors";

/**
 * 任务效果趋势：每 7 天一个窗口的指标值折线（没有数据的窗口断开），基线和目标画成水平虚线，
 * 任务开始日所在的窗口标一条竖线。
 */
export function buildTaskEffectOption(detail: GrowthTaskDetail): EChartsOption {
  const task = detail.view.task;
  const metric = task.metric as GrowthMetric;
  const labels = detail.series.map((p) => p.windowEnd.slice(5));
  const startIndex = detail.series.findIndex((p) => p.containsStart);
  const markLines: Array<Record<string, unknown>> = [];
  if (task.baselineValue !== null) {
    markLines.push({ yAxis: task.baselineValue, name: "基线", lineStyle: { color: "#9aa3b2", type: "dashed" }, label: { formatter: "基线" } });
  }
  markLines.push({ yAxis: task.targetValue, name: "目标", lineStyle: { color: FORECAST, type: "dashed" }, label: { formatter: "目标" } });
  if (startIndex >= 0) {
    markLines.push({ xAxis: startIndex, name: "开始", lineStyle: { color: "#14a38b", type: "solid" }, label: { formatter: "开始" } });
  }
  return {
    tooltip: {
      trigger: "axis",
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      valueFormatter: (value: any) => formatMetricValue(metric, value === null || value === undefined ? null : Number(value)),
    },
    grid: { left: 12, right: 48, top: 24, bottom: 8, containLabel: true },
    xAxis: { type: "category", data: labels },
    yAxis: {
      type: "value",
      axisLabel: { formatter: (value: number) => (isPercentMetric(metric) ? `${(value * 100).toFixed(0)}%` : String(value)) },
    },
    series: [
      {
        name: "指标值",
        type: "line",
        connectNulls: false,
        symbolSize: 7,
        itemStyle: { color: PRIMARY },
        lineStyle: { color: PRIMARY },
        data: detail.series.map((p) => p.value),
        markLine: { symbol: "none", data: markLines as never },
      },
    ],
  };
}
