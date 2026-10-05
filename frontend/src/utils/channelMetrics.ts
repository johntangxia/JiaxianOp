import type {
  ChannelRow,
  Diagnosis,
  DiagnosisSeverity,
  DistributionMetrics,
  PvRange,
  SourceMix,
} from "../api/types";
import { parseIsoDate, toIsoDate, type DateRange } from "./dateRange";

type Pick = "current" | "previous";

/** 环比变化率：上期缺失或为 0 时没有意义，返回 null。 */
export function changeRate(current: number | null | undefined, previous: number | null | undefined): number | null {
  if (current === null || current === undefined || previous === null || previous === undefined || previous === 0) {
    return null;
  }
  return (current - previous) / previous;
}

/** 0.1234 → "12.3%"，缺失 → "—"。 */
export function formatPercent(value: number | null | undefined, digits = 1): string {
  if (value === null || value === undefined) {
    return "—";
  }
  return `${(value * 100).toFixed(digits)}%`;
}

/** 85 → "1 分 25 秒"，45 → "45 秒"，缺失 → "—"。 */
export function formatSeconds(value: number | null | undefined): string {
  if (value === null || value === undefined) {
    return "—";
  }
  const total = Math.round(value);
  if (total < 60) {
    return `${total} 秒`;
  }
  return `${Math.floor(total / 60)} 分 ${total % 60} 秒`;
}

export interface DistributionTotals {
  impressions: number;
  clicks: number;
  ctr: number | null;
}

/** 一组频道"自己入口分发"的合计：曝光、点击和整体点击率（不是各频道点击率的平均）。 */
export function sumDistribution(rows: ChannelRow[], pick: Pick): DistributionTotals {
  let impressions = 0;
  let clicks = 0;
  for (const row of rows) {
    const d: DistributionMetrics = row[pick].distribution;
    impressions += d.impressions;
    clicks += d.clicks;
  }
  return { impressions, clicks, ctr: impressions === 0 ? null : clicks / impressions };
}

const EMPTY_MIX: SourceMix = { ownEntry: 0, compositeEntry: 0, search: 0, share: 0, other: 0 };

/** 一组领域频道内容的来源构成合计。 */
export function sumSourceMix(rows: ChannelRow[], pick: Pick): SourceMix & { total: number } {
  const sum = { ...EMPTY_MIX };
  for (const row of rows) {
    const mix = row[pick].content?.sourceMix;
    if (!mix) {
      continue;
    }
    sum.ownEntry += mix.ownEntry;
    sum.compositeEntry += mix.compositeEntry;
    sum.search += mix.search;
    sum.share += mix.share;
    sum.other += mix.other;
  }
  return { ...sum, total: sum.ownEntry + sum.compositeEntry + sum.search + sum.share + sum.other };
}

export function severityTagType(severity: DiagnosisSeverity): "danger" | "warning" | "info" {
  switch (severity) {
    case "CRITICAL":
      return "danger";
    case "WARN":
      return "warning";
    case "INFO":
      return "info";
  }
}

const SEVERITY_ORDER: DiagnosisSeverity[] = ["CRITICAL", "WARN", "INFO"];

/** 最严重的级别，没有诊断时为 null（用来给频道行排序/着色）。 */
export function worstSeverity(diagnoses: Diagnosis[]): DiagnosisSeverity | null {
  for (const severity of SEVERITY_ORDER) {
    if (diagnoses.some((d) => d.severity === severity)) {
      return severity;
    }
  }
  return null;
}

/** 表格排序用：缺失值排在最小。 */
export function compareNullable(a: number | null | undefined, b: number | null | undefined): number {
  const x = a ?? Number.NEGATIVE_INFINITY;
  const y = b ?? Number.NEGATIVE_INFINITY;
  return x === y ? 0 : x < y ? -1 : 1;
}

export const DEFAULT_COCKPIT_DAYS = 180;

/**
 * 驾驶舱默认区间：PV 数据最后一天往前共 days 天（含最后一天），起点不早于数据第一天；没有数据时返回 null。
 * 默认取近半年：领域频道有 23 个，区间太短时很多频道没有足够的数据量，诊断规则不会下结论。
 */
export function defaultCockpitRange(pvRange: PvRange | null, days = DEFAULT_COCKPIT_DAYS): DateRange | null {
  if (!pvRange?.from || !pvRange.to) {
    return null;
  }
  const end = parseIsoDate(pvRange.to);
  const start = new Date(end);
  start.setDate(start.getDate() - (days - 1));
  const first = parseIsoDate(pvRange.from);
  return { from: toIsoDate(start < first ? first : start), to: pvRange.to };
}
