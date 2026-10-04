import type { EChartsOption } from "echarts";
import type { PvTrend } from "../api/types";
import { formatNumber, formatPeriod, formatRate } from "../utils/format";
import { FORECAST, FORECAST_BAND, PRIMARY } from "./colors";

/** 横轴：实际周期加上还没出现在其中的预测周期（预测的第一期可能就是进行中的那个周期）。 */
function periodStarts(trend: PvTrend): string[] {
  const starts = trend.points.map((point) => point.periodStart);
  for (const forecast of trend.forecast) {
    if (!starts.includes(forecast.periodStart)) {
      starts.push(forecast.periodStart);
    }
  }
  return starts;
}

/** 悬浮提示：实际值（含进行中标注、环比、同比）和预测值（含置信区间）。 */
export function trendTooltip(trend: PvTrend, starts: string[], index: number): string {
  const start = starts[index];
  const point = trend.points.find((p) => p.periodStart === start);
  const forecast = trend.forecast.find((f) => f.periodStart === start);
  const lines = [`<strong>${formatPeriod(start, trend.granularity)}</strong>`];
  if (point) {
    lines.push(`实际 PV：${formatNumber(point.pv)}${point.complete ? "" : "（进行中）"}`);
    lines.push(`环比：${formatRate(point.momRate)}　同比：${formatRate(point.yoyRate)}`);
  }
  if (forecast) {
    lines.push(`预测 PV：${formatNumber(forecast.value)}`);
    lines.push(`置信区间：${formatNumber(forecast.lower)} ~ ${formatNumber(forecast.upper)}`);
  }
  return lines.join("<br/>");
}

/**
 * 趋势图：实线是实际值（进行中的周期用空心点）；虚线是预测，从最后一个已结束周期接出；
 * 置信区间用两条堆叠的线实现（透明的下限 + 浅色的区间宽度）。
 */
export function buildTrendOption(trend: PvTrend): EChartsOption {
  const starts = periodStarts(trend);
  const indexOf = (start: string): number => starts.indexOf(start);

  const actual: Array<number | null | { value: number; itemStyle: object }> = starts.map(() => null);
  for (const point of trend.points) {
    actual[indexOf(point.periodStart)] = point.complete
      ? point.pv
      : { value: point.pv, itemStyle: { color: "#fff", borderColor: PRIMARY, borderWidth: 2 } };
  }

  const forecastLine: Array<number | null> = starts.map(() => null);
  const bandBase: Array<number | null> = starts.map(() => null);
  const bandWidth: Array<number | null> = starts.map(() => null);
  const lastComplete = [...trend.points].reverse().find((point) => point.complete);
  if (trend.forecast.length > 0 && lastComplete) {
    const bridge = indexOf(lastComplete.periodStart);
    forecastLine[bridge] = lastComplete.pv;
    bandBase[bridge] = lastComplete.pv;
    bandWidth[bridge] = 0;
  }
  for (const forecast of trend.forecast) {
    const i = indexOf(forecast.periodStart);
    forecastLine[i] = forecast.value;
    bandBase[i] = forecast.lower;
    bandWidth[i] = forecast.upper - forecast.lower;
  }

  return {
    color: [PRIMARY, FORECAST],
    grid: { left: 64, right: 24, top: 44, bottom: 36 },
    legend: { data: ["实际", "预测", "预测区间"], top: 4 },
    tooltip: {
      trigger: "axis",
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      formatter: (params: any) => trendTooltip(trend, starts, Array.isArray(params) ? params[0].dataIndex : params.dataIndex),
    },
    xAxis: {
      type: "category",
      boundaryGap: false,
      data: starts.map((start) => formatPeriod(start, trend.granularity)),
    },
    yAxis: { type: "value", axisLabel: { formatter: (value: number) => formatNumber(value) } },
    series: [
      {
        name: "实际",
        type: "line",
        data: actual,
        symbolSize: 7,
        lineStyle: { color: PRIMARY, width: 2 },
        itemStyle: { color: PRIMARY },
      },
      {
        name: "预测",
        type: "line",
        data: forecastLine,
        symbolSize: 7,
        lineStyle: { color: FORECAST, width: 2, type: "dashed" },
        itemStyle: { color: FORECAST },
      },
      {
        name: "区间下限",
        type: "line",
        stack: "band",
        data: bandBase,
        symbol: "none",
        lineStyle: { opacity: 0 },
        silent: true,
        tooltip: { show: false },
      },
      {
        name: "预测区间",
        type: "line",
        stack: "band",
        data: bandWidth,
        symbol: "none",
        lineStyle: { opacity: 0 },
        areaStyle: { color: FORECAST_BAND },
        silent: true,
        tooltip: { show: false },
      },
    ],
  };
}
