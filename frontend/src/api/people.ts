import { getJson } from "./http";
import { buildQuery } from "./query";
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

/** 创作者排行导出 CSV 的下载地址（全部，不分页）：排序和部门筛选同排行。 */
export function creatorRankingExportUrl(from: string, to: string, sort: CreatorSortKey, dept: string | undefined): string {
  return "/api/creators/ranking/export" + buildQuery({ from, to, sort, dept });
}

/** 用户列表导出 CSV 的下载地址（全部符合条件的用户，不分页）：筛选和排序同用户列表。 */
export function userListExportUrl(query: Omit<UserListQuery, "limit" | "offset">): string {
  return "/api/users/export" + buildQuery({ ...query });
}
