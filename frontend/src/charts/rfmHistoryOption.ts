import type { EChartsOption } from "echarts";
import type { HistoryPoint } from "../api/types";
import { RFM_F, RFM_M, RFM_R } from "./colors";

/** 悬浮提示：当周的分层标签（带预警）、名次和三个分数。 */
export function rfmHistoryTooltip(history: HistoryPoint[], index: number): string {
  const point = history[index];
  return [
    `<strong>${point.asOf}</strong>`,
    `分层：${point.segmentName}${point.churnAlert ? "（预警）" : ""}　名次 ${point.rank}`,
    `R ${point.rScore}　F ${point.fScore}　M ${point.mScore}`,
  ].join("<br/>");
}

/** RFM 三个分数（1~5）每周的变化折线。 */
export function buildRfmHistoryOption(history: HistoryPoint[]): EChartsOption {
  return {
    color: [RFM_R, RFM_F, RFM_M],
    grid: { left: 40, right: 24, top: 44, bottom: 32 },
    legend: { data: ["新近度 R", "频次 F", "产出 M"], top: 4 },
    tooltip: {
      trigger: "axis",
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      formatter: (params: any) => rfmHistoryTooltip(history, Array.isArray(params) ? params[0].dataIndex : params.dataIndex),
    },
    xAxis: { type: "category", boundaryGap: false, data: history.map((point) => point.asOf) },
    yAxis: { type: "value", min: 0, max: 5, interval: 1 },
    series: [
      { name: "新近度 R", type: "line", data: history.map((point) => point.rScore), symbolSize: 7 },
      { name: "频次 F", type: "line", data: history.map((point) => point.fScore), symbolSize: 7 },
      { name: "产出 M", type: "line", data: history.map((point) => point.mScore), symbolSize: 7 },
    ],
  };
}
