import type { EChartsOption } from "echarts";
import type { LeaderboardEntry } from "../api/types";
import { formatNumber } from "../utils/format";
import { ACTIVE, PASSIVE } from "./colors";

/** 榜单前几名的主动/被动积分堆叠横向条形图：第一名在最上面。 */
export function buildLeaderboardOption(entries: LeaderboardEntry[]): EChartsOption {
  return {
    color: [ACTIVE, PASSIVE],
    grid: { left: 12, right: 24, top: 36, bottom: 8, containLabel: true },
    legend: { data: ["主动积分", "被动积分"], top: 4 },
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    xAxis: { type: "value", axisLabel: { formatter: (value: number) => formatNumber(value) } },
    yAxis: { type: "category", inverse: true, data: entries.map((entry) => entry.name) },
    series: [
      { name: "主动积分", type: "bar", stack: "points", barMaxWidth: 22, data: entries.map((e) => e.activePoints) },
      { name: "被动积分", type: "bar", stack: "points", barMaxWidth: 22, data: entries.map((e) => e.passivePoints) },
    ],
  };
}
