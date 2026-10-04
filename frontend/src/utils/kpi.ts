import type { ForecastPoint, PvPoint, PvTrend } from "../api/types";

export interface Kpis {
  /** 趋势里所有周期的 PV 合计（周期按粒度向前/向后扩展过，所以可能略多于所选日期范围）。 */
  totalPv: number;
  /** 最近一个已结束的周期，没有则为 null。 */
  latest: PvPoint | null;
  /** 第一期预测，没有预测则为 null。 */
  nextForecast: ForecastPoint | null;
}

export function deriveKpis(trend: PvTrend): Kpis {
  const totalPv = trend.points.reduce((sum, point) => sum + point.pv, 0);
  let latest: PvPoint | null = null;
  for (let i = trend.points.length - 1; i >= 0; i--) {
    if (trend.points[i].complete) {
      latest = trend.points[i];
      break;
    }
  }
  return { totalPv, latest, nextForecast: trend.forecast[0] ?? null };
}
