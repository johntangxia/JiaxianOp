import { getJson } from "./http";
import type {
  AccountOverview,
  BloggerOverview,
  ChannelAccounts,
  CompareType,
  Comparison,
  ContentOverview,
  Granularity,
} from "./types";

export function fetchChannelAccounts(channelId: number, from: string, to: string): Promise<ChannelAccounts> {
  return getJson<ChannelAccounts>(`/api/drill/channels/${channelId}/accounts`, { from, to });
}

export function fetchAccount(id: number, from: string, to: string, granularity: Granularity): Promise<AccountOverview> {
  return getJson<AccountOverview>(`/api/drill/accounts/${id}`, { from, to, granularity });
}

export function fetchBlogger(id: number, from: string, to: string, granularity: Granularity): Promise<BloggerOverview> {
  return getJson<BloggerOverview>(`/api/drill/bloggers/${id}`, { from, to, granularity });
}

export function fetchContent(id: number, from: string, to: string, granularity: Granularity): Promise<ContentOverview> {
  return getJson<ContentOverview>(`/api/drill/contents/${id}`, { from, to, granularity });
}

export function fetchComparison(
  type: CompareType,
  ids: number[],
  from: string,
  to: string,
  granularity: Granularity,
): Promise<Comparison> {
  return getJson<Comparison>("/api/compare", { type, ids: ids.join(","), from, to, granularity });
}
