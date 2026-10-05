import type { EChartsOption } from "echarts";
import type { MixSlice } from "../api/types";
import { formatNumber } from "../utils/format";
import {
  SOURCE_COMPOSITE_ENTRY,
  SOURCE_OTHER,
  SOURCE_OWN_ENTRY,
  SOURCE_SEARCH,
  SOURCE_SHARE,
} from "./colors";

const SOURCE_COLORS: Record<string, string> = {
  OWN_ENTRY: SOURCE_OWN_ENTRY,
  COMPOSITE_ENTRY: SOURCE_COMPOSITE_ENTRY,
  SEARCH: SOURCE_SEARCH,
  SHARE: SOURCE_SHARE,
  OTHER: SOURCE_OTHER,
};

/** 构成饼图：值为 0 的块不画；来源构成用固定配色，领域构成用默认色板。 */
export function buildMixPieOption(slices: MixSlice[]): EChartsOption {
  const visible = slices.filter((slice) => slice.value > 0);
  return {
    tooltip: {
      trigger: "item",
      formatter: (params: unknown) => {
        const p = params as { name: string; value: number; percent: number };
        return `${p.name}<br/>${formatNumber(p.value)}（${p.percent.toFixed(1)}%）`;
      },
    },
    legend: { orient: "vertical", right: 0, top: "middle" },
    series: [
      {
        type: "pie",
        radius: ["45%", "70%"],
        center: ["38%", "50%"],
        label: { show: false },
        data: visible.map((slice) => ({
          name: slice.label,
          value: slice.value,
          ...(SOURCE_COLORS[slice.key] && { itemStyle: { color: SOURCE_COLORS[slice.key] } }),
        })),
      },
    ],
  };
}
