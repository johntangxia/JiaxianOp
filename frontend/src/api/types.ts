export type Granularity = "DAY" | "WEEK" | "MONTH" | "YEAR";
export type ContentType = "ARTICLE" | "VIDEO";
export type PvDimension = "CHANNEL" | "ACCOUNT" | "EMPLOYEE" | "CONTENT_TYPE";

export interface PvPoint {
  periodStart: string;
  pv: number;
  momRate: number | null;
  yoyRate: number | null;
  complete: boolean;
}

export interface ForecastPoint {
  periodStart: string;
  value: number;
  lower: number;
  upper: number;
}

export interface PvTrend {
  granularity: Granularity;
  from: string;
  to: string;
  points: PvPoint[];
  forecast: ForecastPoint[];
}

export interface PvTopEntry {
  id: number | null;
  name: string;
  pv: number;
}

/** 可选过滤条件，多个条件取交集。 */
export interface PvFilter {
  channelId?: number;
  accountId?: number;
  employeeId?: number;
  contentType?: ContentType;
}

export interface PvRange {
  from: string | null;
  to: string | null;
}

export interface ChannelOption {
  id: number;
  name: string;
  category: string;
}

export interface AccountOption {
  id: number;
  name: string;
  type: "PERSONAL" | "TEAM";
}

export interface EmployeeOption {
  id: number;
  name: string;
  dept: string | null;
}

export type PeriodType = "month" | "year";
export type LeaderboardStatus = "IN_PROGRESS" | "ARCHIVED";

export interface LeaderboardEntry {
  rank: number;
  employeeId: number;
  name: string;
  points: number;
  activePoints: number;
  passivePoints: number;
}

export interface Leaderboard {
  periodType: "MONTH" | "YEAR";
  periodValue: string;
  status: LeaderboardStatus;
  source: "LIVE" | "SNAPSHOT";
  entries: LeaderboardEntry[];
}

export interface PeriodOption {
  periodValue: string;
  status: LeaderboardStatus;
}

// ---- KOL ----

export type Segment = "STAR_KOL" | "STEADY" | "RISING" | "CHURN_RISK" | "DORMANT" | "OBSERVE";

export interface BloggerItem {
  employeeId: number;
  name: string;
  rank: number;
  rollingPoints12m: number;
  recencyDays: number | null;
  frequency: number;
  monetary: number;
  rScore: number;
  fScore: number;
  mScore: number;
  segment: Segment;
  segmentName: string;
  churnAlert: boolean;
  recommendation: string | null;
}

export interface BloggerList {
  asOf: string | null;
  total: number;
  segmentCounts: Record<Segment, number>;
  items: BloggerItem[];
}

export interface EmployeeInfo {
  id: number;
  name: string;
  dept: string | null;
  joinDate: string | null;
}

export interface AlertDetail {
  metric: "ACTIVITY" | "POINTS";
  recentDaily: number;
  baselineDaily: number;
  dropRate: number;
}

export interface CurrentStanding {
  asOf: string;
  rank: number;
  segment: Segment;
  segmentName: string;
  churnAlert: boolean;
  recommendation: string | null;
  rollingPoints12m: number;
  recencyDays: number | null;
  frequency: number;
  monetary: number;
  rScore: number;
  fScore: number;
  mScore: number;
  alert: AlertDetail | null;
}

export interface HistoryPoint {
  asOf: string;
  rank: number;
  segment: Segment;
  segmentName: string;
  churnAlert: boolean;
  rScore: number;
  fScore: number;
  mScore: number;
}

export interface SegmentChange {
  asOf: string;
  from: Segment;
  to: Segment;
}

export interface RecentEvent {
  time: string;
  type: "POST" | "COMMENT" | "LOGIN";
  refId: number | null;
}

export interface BloggerProfile {
  employee: EmployeeInfo;
  current: CurrentStanding | null;
  history: HistoryPoint[];
  segmentChanges: SegmentChange[];
  recentEvents: RecentEvent[];
}

export interface ActivityPoint {
  periodStart: string;
  posts: number;
  comments: number;
  logins: number;
  complete: boolean;
}

export interface ActivitySeries {
  granularity: Granularity;
  from: string;
  to: string;
  points: ActivityPoint[];
}

export interface PointsTrendPoint {
  periodStart: string;
  activePoints: number;
  passivePoints: number;
  total: number;
  complete: boolean;
}

export interface PointsTrendSeries {
  granularity: Granularity;
  from: string;
  to: string;
  points: PointsTrendPoint[];
}

export interface KolRebuildResult {
  asOf: string;
  population: number;
  alerts: number;
}

// ---- 建议文案库 ----

export type PlaybookAlertStatus = "NO_ALERT" | "ALERT";

export interface PlaybookEntry {
  id: number;
  segmentLabel: Segment;
  alertStatus: PlaybookAlertStatus;
  recommendationText: string;
}

export interface PlaybookRequest {
  segmentLabel: Segment;
  alertStatus: PlaybookAlertStatus;
  recommendationText: string;
}

// ---- 积分权重 ----

export interface WeightVersion {
  id: number;
  weight: number;
  effectiveDate: string;
}

export interface PointsConfigItem {
  actionType: string;
  category: "ACTIVE" | "PASSIVE";
  current: WeightVersion;
  versions: WeightVersion[];
}

// ---- 模拟器与数据任务 ----

export interface BackfillRequest {
  batchId: string;
  employeeCount: number;
  accountCount: number;
  teamAccountRatio: number;
  contentCount: number;
  historyStart: string;
  historyEnd: string;
  randomSeed: number;
  personas: boolean;
}

export interface RealtimeRequest {
  batchId?: string;
  intervalMillis: number;
  eventsPerBatch: number;
}

export interface RealtimeStatus {
  running: boolean;
  batchId: string | null;
  sentCount: number;
  startedAt: string | null;
}

export interface PvRebuildResult {
  from: string | null;
  to: string | null;
  rowsWritten: number;
}

export interface SnapshotSummary {
  months: number;
  years: number;
  rowsWritten: number;
}

// ---- 频道驾驶舱 ----

export interface DistributionMetrics {
  impressions: number;
  clicks: number;
  ctr: number | null;
  reachUv: number;
  clickUv: number;
  avgDwellSeconds: number | null;
}

export interface SourceMix {
  ownEntry: number;
  compositeEntry: number;
  search: number;
  share: number;
  other: number;
}

export interface ContentMetrics {
  pv: number;
  uv: number;
  interactions: number;
  interactionRate: number | null;
  top1Share: number | null;
  top3Share: number | null;
  sourceMix: SourceMix;
  ownEntryShare: number | null;
  compositeDependency: number | null;
}

export interface SupplyMetrics {
  contents: number;
  authors: number;
}

export interface CompositeCoverage {
  domainsCovered: number;
  topDomainShare: number | null;
}

/** 综合频道的 content/supply 为 null，领域频道的 coverage 为 null。 */
export interface ChannelPeriod {
  distribution: DistributionMetrics;
  content: ContentMetrics | null;
  supply: SupplyMetrics | null;
  coverage: CompositeCoverage | null;
}

export type DiagnosisSeverity = "CRITICAL" | "WARN" | "INFO";

export interface Diagnosis {
  code: string;
  severity: DiagnosisSeverity;
  message: string;
}

export interface ChannelRow {
  id: number;
  name: string;
  category: string;
  current: ChannelPeriod;
  previous: ChannelPeriod;
  diagnoses: Diagnosis[];
}

export interface Cockpit {
  from: string;
  to: string;
  previousFrom: string;
  previousTo: string;
  composite: ChannelRow[];
  domain: ChannelRow[];
  compositeBenchmark: Record<string, number>;
  domainBenchmark: Record<string, number>;
}

export interface TrendPoint {
  periodStart: string;
  ownEntry: number;
  compositeEntry: number;
  search: number;
  share: number;
  other: number;
  impressions: number;
  clicks: number;
  ctr: number | null;
}

export interface MixSlice {
  key: string;
  label: string;
  value: number;
}

export interface TopContent {
  contentId: number;
  type: "ARTICLE" | "VIDEO";
  accountName: string;
  publishTime: string;
  pv: number;
  interactions: number;
}

export interface ChannelDetail {
  row: ChannelRow;
  granularity: Granularity;
  trend: TrendPoint[];
  composition: MixSlice[];
  topContents: TopContent[];
}
