import type { Granularity, PvRange } from "../api/types";

export interface DateRange {
  from: string;
  to: string;
}

export type PickerType = "daterange" | "monthrange" | "yearrange";

const pad = (n: number): string => String(n).padStart(2, "0");

/** 本地时间的 yyyy-MM-dd（不要用 toISOString，它会转成 UTC，在东八区会差一天）。 */
export function toIsoDate(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

export function parseIsoDate(iso: string): Date {
  const [year, month, day] = iso.split("-").map(Number);
  return new Date(year, month - 1, day);
}

function addDays(date: Date, days: number): Date {
  const result = new Date(date);
  result.setDate(result.getDate() + days);
  return result;
}

/**
 * 默认范围：以数据范围的最后一天为终点，往前取日 30 天、周 26 周、月 12 个月、年 5 年，
 * 起点不早于数据范围的第一天；没有数据范围时以 today 为终点。
 */
export function defaultRange(
  granularity: Granularity,
  pvRange: PvRange | null,
  today: Date = new Date(),
): DateRange {
  const end = pvRange?.to ? parseIsoDate(pvRange.to) : today;
  let start: Date;
  switch (granularity) {
    case "DAY":
      start = addDays(end, -29);
      break;
    case "WEEK":
      start = addDays(end, -7 * 25);
      break;
    case "MONTH":
      start = new Date(end.getFullYear(), end.getMonth() - 11, 1);
      break;
    case "YEAR":
      start = new Date(end.getFullYear() - 4, 0, 1);
      break;
  }
  if (pvRange?.from) {
    const earliest = parseIsoDate(pvRange.from);
    if (start < earliest) {
      start = earliest;
    }
  }
  return { from: toIsoDate(start), to: toIsoDate(end) };
}

export function pickerType(granularity: Granularity): PickerType {
  switch (granularity) {
    case "MONTH":
      return "monthrange";
    case "YEAR":
      return "yearrange";
    default:
      return "daterange";
  }
}

/** 选择器选出的两个日期换算成请求范围：月选择取整月，年选择取整年。 */
export function rangeFromPicker(granularity: Granularity, [start, end]: [Date, Date]): DateRange {
  switch (granularity) {
    case "MONTH":
      return {
        from: toIsoDate(new Date(start.getFullYear(), start.getMonth(), 1)),
        to: toIsoDate(new Date(end.getFullYear(), end.getMonth() + 1, 0)),
      };
    case "YEAR":
      return {
        from: toIsoDate(new Date(start.getFullYear(), 0, 1)),
        to: toIsoDate(new Date(end.getFullYear(), 11, 31)),
      };
    default:
      return { from: toIsoDate(start), to: toIsoDate(end) };
  }
}

export function pickerValue(_granularity: Granularity, range: DateRange): [Date, Date] {
  return [parseIsoDate(range.from), parseIsoDate(range.to)];
}
