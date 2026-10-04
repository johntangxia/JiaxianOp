import { getJson, sendJson } from "./http";
import type {
  ActivitySeries,
  BloggerList,
  BloggerProfile,
  Granularity,
  KolRebuildResult,
  PointsTrendSeries,
  Segment,
} from "./types";

export interface BloggerQuery {
  asOf?: string;
  segment?: Segment;
  alert?: boolean;
  limit: number;
  offset: number;
}

export function fetchBloggers(query: BloggerQuery): Promise<BloggerList> {
  return getJson<BloggerList>("/api/bloggers/top1000", { ...query });
}

export function fetchRuns(): Promise<string[]> {
  return getJson<string[]>("/api/bloggers/runs");
}

export function fetchProfile(id: number, weeks = 26): Promise<BloggerProfile> {
  return getJson<BloggerProfile>(`/api/bloggers/${id}/profile`, { weeks });
}

export function fetchActivity(id: number, granularity: Granularity): Promise<ActivitySeries> {
  return getJson<ActivitySeries>(`/api/bloggers/${id}/activity`, { granularity });
}

export function fetchPointsTrend(id: number, granularity: Granularity): Promise<PointsTrendSeries> {
  return getJson<PointsTrendSeries>(`/api/bloggers/${id}/points-trend`, { granularity });
}

export function rebuildKol(asOf: string | undefined, weeks: number): Promise<KolRebuildResult[]> {
  return sendJson<KolRebuildResult[]>("POST", "/api/admin/analytics/kol/rebuild", { params: { asOf, weeks } });
}
