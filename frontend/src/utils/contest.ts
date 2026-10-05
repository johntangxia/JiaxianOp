import type { ContestType, PeriodOption } from "../api/types";

export type MoveKind = "up" | "down" | "flat" | "none";

export interface RankMove {
  text: string;
  kind: MoveKind;
}

/** 名次变化：正数上升（↑2），负数下降（↓3），0 持平，上期没有数据显示"—"。 */
export function rankMove(change: number | null | undefined): RankMove {
  if (change === null || change === undefined) {
    return { text: "—", kind: "none" };
  }
  if (change > 0) {
    return { text: `↑${change}`, kind: "up" };
  }
  if (change < 0) {
    return { text: `↓${-change}`, kind: "down" };
  }
  return { text: "持平", kind: "flat" };
}

/** 健康分，保留一位小数。 */
export function formatScore(score: number): string {
  return score.toFixed(1);
}

/** 健康分变化："+12.3" / "-5.0" / "—"。 */
export function scoreChangeText(change: number | null | undefined): string {
  if (change === null || change === undefined) {
    return "—";
  }
  return `${change > 0 ? "+" : ""}${change.toFixed(1)}`;
}

/** 周期的展示文字：周榜 "2025-06-16 ~ 06-22"，月榜 "2025 年 6 月"，没过完的加"（进行中）"。 */
export function periodLabel(type: ContestType, period: PeriodOption): string {
  const text =
    type === "WEEK"
      ? `${period.periodStart} ~ ${period.periodEnd.slice(5)}`
      : `${period.periodStart.slice(0, 4)} 年 ${Number(period.periodStart.slice(5, 7))} 月`;
  return period.complete ? text : `${text}（进行中）`;
}

/** 默认周期：最近一个已结束的，没有已结束的就取最新的；没有周期返回 null。 */
export function defaultPeriod(periods: PeriodOption[]): PeriodOption | null {
  return periods.find((p) => p.complete) ?? periods[0] ?? null;
}

/** 前三名的奖牌样式类，其余为空串。 */
export function medalClass(rank: number): string {
  return rank === 1 ? "medal-gold" : rank === 2 ? "medal-silver" : rank === 3 ? "medal-bronze" : "";
}

/** 维度得分条的颜色：≥ 70 绿，≥ 40 蓝，其余灰。 */
export function scoreColor(score: number): string {
  return score >= 70 ? "#1a9b56" : score >= 40 ? "#2f6fed" : "#9aa3b2";
}
