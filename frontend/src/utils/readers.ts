import type { Granularity, ReaderRoleKey } from "../api/types";

/** 同期群行的标签：周 "2025-06-02 周"，月 "2025-06"。 */
export function cohortLabel(cohortStart: string, granularity: Granularity): string {
  return granularity === "MONTH" ? cohortStart.slice(0, 7) : `${cohortStart} 周`;
}

/** 留存表头：第 0、1、2… 个周期，单位随粒度变。 */
export function periodHeader(index: number, granularity: Granularity): string {
  const unit = granularity === "MONTH" ? "月" : "周";
  return index === 0 ? `加入当${unit}` : `第 ${index} ${unit}`;
}

export interface HeatCell {
  text: string;
  background: string;
  color: string;
}

/** 留存热力格：没有数据显示"—"和浅灰底；留存越高蓝色越深，超过 60% 用白字。 */
export function heatCell(value: number | null | undefined): HeatCell {
  if (value === null || value === undefined) {
    return { text: "—", background: "#f5f7fa", color: "#9aa3b2" };
  }
  const alpha = 0.08 + 0.82 * Math.min(1, Math.max(0, value));
  return { text: `${(value * 100).toFixed(0)}%`, background: `rgba(47, 111, 237, ${alpha.toFixed(2)})`, color: value > 0.6 ? "#fff" : "#1f2937" };
}

const ROLE_COLORS: Record<ReaderRoleKey, string> = {
  CREATOR: "#8b5cf6",
  INTERACTOR: "#2f6fed",
  LURKER: "#f08c2c",
  LOGIN_ONLY: "#9aa3b2",
  INACTIVE: "#d5d9e0",
};

export function roleColor(role: ReaderRoleKey): string {
  return ROLE_COLORS[role];
}

/** yyyy-MM-ddTHH:mm:ss → yyyy-MM-dd；缺失 → "—"。 */
export function dateOnly(value: string | null | undefined): string {
  return value ? value.slice(0, 10) : "—";
}

/** 同期群人数少于这个数时，留存率会因为一两个人而剧烈波动，表格里整行置灰并提示。 */
export const MIN_COHORT_SIZE = 5;

export function isSmallCohort(size: number): boolean {
  return size < MIN_COHORT_SIZE;
}
