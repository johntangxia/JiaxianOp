import { getJson } from "./http";
import type { ContestBoards, ContestType, ContestPeriod } from "./types";

export function fetchPeriods(type: ContestType): Promise<ContestPeriod[]> {
  return getJson<ContestPeriod[]>("/api/contest/periods", { type });
}

export function fetchBoards(type: ContestType, periodStart: string): Promise<ContestBoards> {
  return getJson<ContestBoards>("/api/contest/boards", { type, periodStart });
}
