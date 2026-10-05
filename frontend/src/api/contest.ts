import { getJson } from "./http";
import type { ContestBoards, ContestType, PeriodOption } from "./types";

export function fetchPeriods(type: ContestType): Promise<PeriodOption[]> {
  return getJson<PeriodOption[]>("/api/contest/periods", { type });
}

export function fetchBoards(type: ContestType, periodStart: string): Promise<ContestBoards> {
  return getJson<ContestBoards>("/api/contest/boards", { type, periodStart });
}
