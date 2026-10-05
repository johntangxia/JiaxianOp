import { getJson } from "./http";
import type {
  Granularity,
  PvRange,
  ReaderActivityReport,
  ReaderFunnel,
  ReaderLurkerPage,
  ReaderSegments,
  RetentionReport,
} from "./types";

export function fetchReaderActivity(from: string, to: string, granularity: Granularity): Promise<ReaderActivityReport> {
  return getJson<ReaderActivityReport>("/api/readers/activity", { from, to, granularity });
}

export function fetchRetention(granularity: "WEEK" | "MONTH", from: string, to: string, periods?: number): Promise<RetentionReport> {
  return getJson<RetentionReport>("/api/readers/retention", { granularity, from, to, periods });
}

export function fetchFunnel(from: string, to: string): Promise<ReaderFunnel> {
  return getJson<ReaderFunnel>("/api/readers/funnel", { from, to });
}

export function fetchSegments(from: string, to: string): Promise<ReaderSegments> {
  return getJson<ReaderSegments>("/api/readers/segments", { from, to });
}

export function fetchLurkers(from: string, to: string, dept: string | undefined, limit: number, offset: number): Promise<ReaderLurkerPage> {
  return getJson<ReaderLurkerPage>("/api/readers/lurkers", { from, to, dept, limit, offset });
}

/** 登录/发帖行为的起止日（模拟数据里终点是模拟的"现在"）；没有数据时 from/to 都是 null。 */
export function fetchReaderRange(): Promise<PvRange> {
  return getJson<PvRange>("/api/readers/range");
}
