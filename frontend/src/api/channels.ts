import { getJson } from "./http";
import type { ChannelDetail, Cockpit, Granularity } from "./types";

export function fetchCockpit(from: string, to: string): Promise<Cockpit> {
  return getJson<Cockpit>("/api/channels/cockpit", { from, to });
}

export function fetchChannelDetail(id: number, from: string, to: string, granularity: Granularity): Promise<ChannelDetail> {
  return getJson<ChannelDetail>(`/api/channels/${id}/detail`, { from, to, granularity });
}
