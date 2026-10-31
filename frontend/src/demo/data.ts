import snapshot from "./snapshot.json";
import type {
  BloggerItem,
  ChannelRow,
  Cockpit,
  ContestBoards,
  CreatorOverviewReport,
  Granularity,
  PvDimension,
  PvTopEntry,
  PvTrend,
  ReaderActivityReport,
  ReaderFunnel,
  ReaderSegments,
  Segment,
  UserProfile,
} from "../api/types";

export interface DemoCreatorRow {
  name: string;
  dept: string | null;
  posts: number;
  pv: number;
  uv: number;
  interactions: number;
  interactionRate: number | null;
  completionRate: number | null;
  newFollowers: number;
}

export interface DemoSnapshot {
  snapshotDate: string;
  pv: PvTrend;
  top: Record<PvDimension, PvTopEntry[]>;
  cockpit: Cockpit;
  boards: Record<"WEEK" | "MONTH", ContestBoards>;
  readerActivity: ReaderActivityReport;
  readerFunnel: ReaderFunnel;
  readerSegments: ReaderSegments;
  creatorOverview: CreatorOverviewReport;
  creatorRanking: DemoCreatorRow[];
  kol: { asOf: string; sourceTotal: number; items: BloggerItem[] };
  users: UserProfile[];
}

export const demoData = snapshot as unknown as DemoSnapshot;

export function findChannel(id: number): ChannelRow | undefined {
  return [...demoData.cockpit.composite, ...demoData.cockpit.domain].find((row) => row.id === id);
}

export function getContestBoard(type: "WEEK" | "MONTH"): ContestBoards {
  return demoData.boards[type];
}

export function filterKol(filters: { segment?: Segment; alertOnly?: boolean }): BloggerItem[] {
  return demoData.kol.items.filter(
    (item) => (!filters.segment || item.segment === filters.segment) && (!filters.alertOnly || item.churnAlert),
  );
}

export function defaultDemoCompareIds(): number[] {
  return demoData.cockpit.domain.slice(0, 2).map((row) => row.id);
}

export const demoGranularity: Granularity = "MONTH";
