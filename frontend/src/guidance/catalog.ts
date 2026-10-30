import type { GuidanceEntry, GuidanceId } from "./types";
import { channels } from "./entries/channels";
import { users } from "./entries/users";
import { kol } from "./entries/kol";
import { other } from "./entries/other";

export const entries = [...channels, ...users, ...kol, ...other];
const byId = new Map(entries.map(e => [e.id, e]));
export function getGuidance(id: GuidanceId): GuidanceEntry {
  const result = byId.get(id);
  if (!result) throw new Error(`未知说明：${id}`);
  return result;
}
const simple: Record<string, string> = {
  overview: "page.overview", "visit-flow": "page.visit-flow", channels: "page.channels", "channel-detail": "page.channel-detail", contest: "page.contest",
  compare: "page.compare", "account-detail": "page.account-detail", "blogger-detail": "page.blogger-detail",
  "content-detail": "page.content-detail", "user-detail": "page.user-detail", leaderboard: "page.leaderboard",
  kol: "page.kol", "kol-detail": "page.kol-detail", simulator: "page.simulator", "points-config": "page.points-config", playbook: "page.playbook",
};
const tabs: Record<string, Record<string, string>> = {
  readers: { activity: "page.readers.activity", retention: "page.readers.retention", funnel: "page.readers.funnel", segments: "page.readers.segments" },
  creators: { overview: "page.creators.overview", ranking: "page.creators.ranking", ladder: "page.creators.ladder", concentration: "page.creators.concentration" },
  users: { lifecycle: "page.users.lifecycle", value: "page.users.value", interests: "page.users.interests", list: "page.users.list" },
  growth: { alerts: "page.growth.alerts", tasks: "page.growth.tasks", hot: "page.growth.hot", departments: "page.growth.departments" },
};
export function getPageGuidance(routeName: string, tab?: string): GuidanceId | null {
  return tabs[routeName]?.[tab ?? ({ readers: "activity", creators: "overview", users: "lifecycle", growth: "alerts" } as Record<string, string>)[routeName]] ?? simple[routeName] ?? null;
}
