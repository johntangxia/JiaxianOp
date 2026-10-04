import type { EChartsOption } from "echarts";
import type { ActivitySeries } from "../api/types";
import { formatPeriod } from "../utils/format";
import { COMMENTS, LOGINS, POSTS } from "./colors";

/** 活跃时间轴：各周期的发帖/评论/登录次数堆叠柱状图。 */
export function buildActivityOption(series: ActivitySeries): EChartsOption {
  const bar = (name: string, data: number[]) => ({ name, type: "bar" as const, stack: "activity", barMaxWidth: 28, data });
  return {
    color: [POSTS, COMMENTS, LOGINS],
    grid: { left: 48, right: 24, top: 40, bottom: 36 },
    legend: { data: ["发帖", "评论", "登录"], top: 4 },
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    xAxis: { type: "category", data: series.points.map((p) => formatPeriod(p.periodStart, series.granularity)) },
    yAxis: { type: "value", minInterval: 1 },
    series: [
      bar("发帖", series.points.map((p) => p.posts)),
      bar("评论", series.points.map((p) => p.comments)),
      bar("登录", series.points.map((p) => p.logins)),
    ],
  };
}
