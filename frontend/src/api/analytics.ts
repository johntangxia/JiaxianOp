import { getJson } from "./http";
import type {
  AccountOption,
  ChannelOption,
  EmployeeOption,
  Granularity,
  PvDimension,
  PvFilter,
  PvRange,
  PvTopEntry,
  PvTrend,
} from "./types";

export interface TrendQuery extends PvFilter {
  granularity: Granularity;
  from: string;
  to: string;
  forecastPeriods: number;
}

export interface TopQuery extends PvFilter {
  dimension: PvDimension;
  from: string;
  to: string;
  limit: number;
}

export function fetchTrend(query: TrendQuery): Promise<PvTrend> {
  return getJson<PvTrend>("/api/analytics/pv/trend", { ...query });
}

export function fetchTop(query: TopQuery): Promise<PvTopEntry[]> {
  return getJson<PvTopEntry[]>("/api/analytics/pv/top", { ...query });
}

export function fetchPvRange(): Promise<PvRange> {
  return getJson<PvRange>("/api/meta/pv-range");
}

export function fetchChannels(): Promise<ChannelOption[]> {
  return getJson<ChannelOption[]>("/api/dimensions/channels");
}

export function searchAccounts(keyword: string, limit = 20): Promise<AccountOption[]> {
  return getJson<AccountOption[]>("/api/dimensions/accounts", { keyword, limit });
}

export function searchEmployees(keyword: string, limit = 20): Promise<EmployeeOption[]> {
  return getJson<EmployeeOption[]>("/api/dimensions/employees", { keyword, limit });
}
