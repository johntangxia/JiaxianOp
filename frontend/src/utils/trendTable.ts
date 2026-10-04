import type { PvTrend } from "../api/types";
import { formatNumber, formatPeriod } from "./format";

export interface TrendRow {
  key: string;
  label: string;
  pv: number;
  momRate: number | null;
  yoyRate: number | null;
  kind: "forecast" | "incomplete" | "complete";
  /** 预测行的置信区间，如 "120 ~ 160"；其他行为 null。 */
  range: string | null;
}

/** 明细表的行：预测行和实际行混排，按周期倒序；同一周期预测行排在实际行前面。 */
export function buildTrendRows(trend: PvTrend): TrendRow[] {
  const entries: Array<{ start: string; order: number; row: TrendRow }> = [];
  for (const f of trend.forecast) {
    entries.push({
      start: f.periodStart,
      order: 1,
      row: {
        key: `forecast-${f.periodStart}`,
        label: formatPeriod(f.periodStart, trend.granularity),
        pv: f.value,
        momRate: null,
        yoyRate: null,
        kind: "forecast",
        range: `${formatNumber(f.lower)} ~ ${formatNumber(f.upper)}`,
      },
    });
  }
  for (const p of trend.points) {
    entries.push({
      start: p.periodStart,
      order: 0,
      row: {
        key: `actual-${p.periodStart}`,
        label: formatPeriod(p.periodStart, trend.granularity),
        pv: p.pv,
        momRate: p.momRate,
        yoyRate: p.yoyRate,
        kind: p.complete ? "complete" : "incomplete",
        range: null,
      },
    });
  }
  entries.sort((a, b) => (a.start === b.start ? b.order - a.order : a.start < b.start ? 1 : -1));
  return entries.map((entry) => entry.row);
}
