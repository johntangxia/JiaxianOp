import { getJson } from "./http";
import type {
  CreatorConcentrationReport,
  CreatorLadderReport,
  CreatorOverviewReport,
  CreatorRankingReport,
  CreatorSortKey,
  Granularity,
  UserInterestReport,
  UserLifecycleReport,
  UserListReport,
  UserListSort,
  UserProfile,
  UserValueReport,
} from "./types";

export function fetchCreatorOverview(from: string, to: string, granularity: Granularity, periods?: number): Promise<CreatorOverviewReport> {
  return getJson<CreatorOverviewReport>("/api/creators/overview", { from, to, granularity, periods });
}

export function fetchCreatorRanking(
  from: string,
  to: string,
  sort: CreatorSortKey,
  dept: string | undefined,
  limit: number,
  offset: number,
): Promise<CreatorRankingReport> {
  return getJson<CreatorRankingReport>("/api/creators/ranking", { from, to, sort, dept, limit, offset });
}

export function fetchCreatorLadder(asOf?: string): Promise<CreatorLadderReport> {
  return getJson<CreatorLadderReport>("/api/creators/ladder", { asOf });
}

export function fetchCreatorConcentration(from: string, to: string): Promise<CreatorConcentrationReport> {
  return getJson<CreatorConcentrationReport>("/api/creators/concentration", { from, to });
}

export function fetchUserLifecycle(asOf?: string): Promise<UserLifecycleReport> {
  return getJson<UserLifecycleReport>("/api/users/lifecycle", { asOf });
}

export function fetchUserValue(asOf?: string): Promise<UserValueReport> {
  return getJson<UserValueReport>("/api/users/value", { asOf });
}

export function fetchUserInterests(asOf: string | undefined, days: number): Promise<UserInterestReport> {
  return getJson<UserInterestReport>("/api/users/interests", { asOf, days });
}

export interface UserListQuery {
  asOf?: string;
  stage?: string;
  value?: string;
  dept?: string;
  q?: string;
  sort: UserListSort;
  limit: number;
  offset: number;
}

export function fetchUserList(query: UserListQuery): Promise<UserListReport> {
  return getJson<UserListReport>("/api/users/list", { ...query });
}

export function fetchUserProfile(id: number, asOf?: string): Promise<UserProfile> {
  return getJson<UserProfile>(`/api/users/${id}`, { asOf });
}
