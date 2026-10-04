import type { Granularity } from "../api/types";

/** 四舍五入成整数并加千分位。 */
export function formatNumber(value: number): string {
  return Math.round(value).toLocaleString("zh-CN");
}

/** 0.05 → "+5.0%"，-0.123 → "-12.3%"，缺失 → "—"。 */
export function formatRate(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) {
    return "—";
  }
  const percent = rate * 100;
  return `${percent > 0 ? "+" : ""}${percent.toFixed(1)}%`;
}

/** 周期起点的展示文字：日 yyyy-MM-dd，周 "yyyy-MM-dd 周"，月 yyyy-MM，年 yyyy。 */
export function formatPeriod(periodStart: string, granularity: Granularity): string {
  switch (granularity) {
    case "DAY":
      return periodStart;
    case "WEEK":
      return `${periodStart} 周`;
    case "MONTH":
      return periodStart.slice(0, 7);
    case "YEAR":
      return periodStart.slice(0, 4);
  }
}

/** 涨红跌绿（国内习惯），没有或为 0 用灰色。 */
export function rateClass(rate: number | null | undefined): "rate-up" | "rate-down" | "rate-none" {
  if (rate === null || rate === undefined || rate === 0) {
    return "rate-none";
  }
  return rate > 0 ? "rate-up" : "rate-down";
}
