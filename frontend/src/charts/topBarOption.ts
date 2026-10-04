import type { EChartsOption } from "echarts";
import type { PvTopEntry } from "../api/types";
import { formatNumber } from "../utils/format";
import { PRIMARY } from "./colors";

/** Top N 横向柱状图：第一名在最上面。 */
export function buildTopBarOption(entries: PvTopEntry[]): EChartsOption {
  return {
    grid: { left: 12, right: 72, top: 8, bottom: 8, containLabel: true },
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    xAxis: { type: "value", axisLabel: { formatter: (value: number) => formatNumber(value) } },
    yAxis: { type: "category", inverse: true, data: entries.map((entry) => entry.name) },
    series: [
      {
        type: "bar",
        data: entries.map((entry) => entry.pv),
        barMaxWidth: 22,
        itemStyle: { color: PRIMARY },
        label: {
          show: true,
          position: "right",
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          formatter: (params: any) => formatNumber(params.value),
        },
      },
    ],
  };
}
