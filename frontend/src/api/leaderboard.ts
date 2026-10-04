import { getJson } from "./http";
import type { Leaderboard, PeriodOption, PeriodType } from "./types";

export function fetchLeaderboard(type: PeriodType, period: string, limit: number): Promise<Leaderboard> {
  return getJson<Leaderboard>(`/api/leaderboard/${type}`, { period, limit });
}

export function fetchPeriods(type: PeriodType): Promise<PeriodOption[]> {
  return getJson<PeriodOption[]>(`/api/leaderboard/${type}/periods`);
}
