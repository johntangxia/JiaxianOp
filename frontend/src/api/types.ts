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

// ---- 频道竞赛榜 ----

export type ContestType = "WEEK" | "MONTH";

export interface ContestPeriod {
  periodStart: string;
  periodEnd: string;
  complete: boolean;
}

export interface DimensionScore {
  key: string;
  label: string;
  weight: number;
  score: number;
}

/** 名次变化 = 上期名次 − 本期名次（正数是上升）；上期没有数据时上期相关字段为 null。 */
export interface ContestEntry {
  rank: number;
  previousRank: number | null;
  rankChange: number | null;
  channelId: number;
  name: string;
  score: number;
  previousScore: number | null;
  scoreChange: number | null;
  dimensions: DimensionScore[];
  strongest: string | null;
  weakest: string | null;
}

export interface ContestBoards {
  type: ContestType;
  periodStart: string;
  periodEnd: string;
  previousStart: string;
  previousEnd: string;
  previousHasData: boolean;
  domain: ContestEntry[];
  composite: ContestEntry[];
  improvers: ContestEntry[];
}

// ---- 下钻与对比 ----

export interface ExposureMetrics {
  impressions: number;
  clicks: number;
  ctr: number | null;
  avgDwellSeconds: number | null;
  completionRate: number | null;
}

export interface FollowerMetrics {
  followers: number;
  newFollowers: number;
  lostFollowers: number;
}

/** followers 只有账号有。博主按"作为作者"统计，合著内容每位作者都记完整的 PV 和互动。 */
export interface SubjectMetrics {
  content: ContentMetrics;
  supply: SupplyMetrics;
  exposure: ExposureMetrics;
  followers: FollowerMetrics | null;
}

export interface DrillTrendPoint extends TrendPoint {
  newFollowers: number;
  lostFollowers: number;
}

export interface BreakdownRow {
  id: number;
  name: string;
  extra: string | null;
  current: SubjectMetrics;
  previous: SubjectMetrics;
}

export interface ChannelAccounts {
  channelId: number;
  channelName: string;
  from: string;
  to: string;
  previousFrom: string;
  previousTo: string;
  accounts: BreakdownRow[];
}

export interface DrillAccountInfo {
  id: number;
  name: string;
  type: "PERSONAL" | "TEAM";
}

export interface DrillPerson {
  id: number;
  name: string;
}

export interface DrillBloggerInfo {
  id: number;
  name: string;
  dept: string | null;
  communityJoinDate: string | null;
  kolProfile: boolean;
}

export interface DrillContentInfo {
  id: number;
  type: "ARTICLE" | "VIDEO";
  channelId: number;
  channelName: string;
  accountId: number;
  accountName: string;
  publishTime: string;
}

export interface EntryExposure {
  channelId: number;
  channelName: string;
  category: string;
  impressions: number;
  clicks: number;
}

interface DrillRanges {
  from: string;
  to: string;
  previousFrom: string;
  previousTo: string;
  granularity: Granularity;
  current: SubjectMetrics;
  previous: SubjectMetrics;
  trend: DrillTrendPoint[];
  composition: MixSlice[];
}

export interface AccountOverview extends DrillRanges {
  account: DrillAccountInfo;
  authors: BreakdownRow[];
  channels: BreakdownRow[];
  topContents: TopContent[];
}

export interface BloggerOverview extends DrillRanges {
  blogger: DrillBloggerInfo;
  accounts: BreakdownRow[];
  channels: BreakdownRow[];
  topContents: TopContent[];
}

export interface ContentOverview extends DrillRanges {
  content: DrillContentInfo;
  authors: DrillPerson[];
  tags: string[];
  entries: EntryExposure[];
}

export type CompareType = "CHANNEL" | "ACCOUNT" | "BLOGGER";

export interface CompareSubject {
  id: number;
  name: string;
  extra: string | null;
  current: SubjectMetrics;
  previous: SubjectMetrics;
  ownEntry: DistributionMetrics | null;
  trend: DrillTrendPoint[];
}

export interface Comparison {
  type: CompareType;
  from: string;
  to: string;
  previousFrom: string;
  previousTo: string;
  granularity: Granularity;
  subjects: CompareSubject[];
}

// ---- 读者侧分析 ----

export interface ReaderActivityPoint {
  periodStart: string;
  activeUsers: number;
  newMembers: number;
  members: number;
  activeRate: number | null;
}

export interface ReaderActivitySummary {
  dau: number;
  wau: number;
  mau: number;
  stickiness: number | null;
  members: number;
}

export interface ReaderActivityReport {
  from: string;
  to: string;
  granularity: Granularity;
  summary: ReaderActivitySummary;
  points: ReaderActivityPoint[];
}

/** retention 第 k 项是加入后第 k 个周期的留存率，数据还没覆盖的周期为 null。 */
export interface RetentionCohort {
  cohortStart: string;
  size: number;
  retention: Array<number | null>;
}

export interface RetentionReport {
  from: string;
  to: string;
  granularity: Granularity;
  periods: number;
  dataEnd: string;
  cohorts: RetentionCohort[];
}

export interface ReaderFunnelStep {
  key: "JOINED" | "VIEWED" | "INTERACTED" | "POSTED" | "RETAINED";
  label: string;
  count: number;
  rateOfJoined: number | null;
}

export interface ReaderFunnel {
  from: string;
  to: string;
  joined: number;
  steps: ReaderFunnelStep[];
}

export type ReaderRoleKey = "CREATOR" | "INTERACTOR" | "LURKER" | "LOGIN_ONLY" | "INACTIVE";

export interface ReaderSegmentCount {
  role: ReaderRoleKey;
  label: string;
  count: number;
  share: number | null;
}

export interface ReaderSegments {
  from: string;
  to: string;
  members: number;
  segments: ReaderSegmentCount[];
}

export interface ReaderLurker {
  employeeId: number;
  name: string;
  dept: string | null;
  joinDate: string | null;
  views: number;
  activeDays: number;
  lastActive: string | null;
}

export interface ReaderLurkerPage {
  from: string;
  to: string;
  total: number;
  limit: number;
  offset: number;
  lurkers: ReaderLurker[];
  depts: string[];
}

// ---- 增长闭环 ----

export interface GrowthDeptPeriod {
  members: number;
  active: number;
  penetration: number | null;
  creators: number;
  interactors: number;
  lurkers: number;
  newMembers: number;
}

export interface GrowthDepartmentRow {
  dept: string;
  current: GrowthDeptPeriod;
  previous: GrowthDeptPeriod;
}

export interface GrowthDepartmentReport {
  from: string;
  to: string;
  previousFrom: string;
  previousTo: string;
  days: number;
  departments: GrowthDepartmentRow[];
  overall: GrowthDepartmentRow;
}

/** hotMultiple/growth/previousPv 只有热点有，qualityMultiple 只有潜力有。 */
export interface GrowthHotRow {
  contentId: number;
  type: "ARTICLE" | "VIDEO";
  channelId: number;
  channelName: string;
  accountId: number;
  accountName: string;
  publishTime: string;
  pv: number;
  previousPv: number | null;
  hotMultiple: number | null;
  growth: number | null;
  qualityMultiple: number | null;
  interactionRate: number | null;
  completionRate: number | null;
}

export interface GrowthHotReport {
  asOf: string;
  hot: GrowthHotRow[];
  potential: GrowthHotRow[];
}

export type GrowthAlertStatus = "OPEN" | "ACKED" | "CLOSED";
export type GrowthEntityType = "CHANNEL" | "ACCOUNT" | "BLOGGER" | "DEPARTMENT" | "OVERALL";

export interface GrowthAlert {
  id: number;
  kind: string;
  severity: "CRITICAL" | "WARN";
  entityType: GrowthEntityType;
  entityId: number | null;
  entityName: string;
  periodEnd: string;
  message: string;
  status: GrowthAlertStatus;
  createdAt: string;
  updatedAt: string;
}

export interface GrowthAlertList {
  total: number;
  limit: number;
  offset: number;
  counts: Record<GrowthAlertStatus, number>;
  alerts: GrowthAlert[];
}

export interface GrowthAlertRebuild {
  asOf: string;
  created: number;
  updated: number;
  total: number;
}

export type GrowthTaskStatus = "TODO" | "DOING" | "DONE" | "CANCELLED";
export type GrowthTaskType =
  | "PUSH_HOT_CONTENT"
  | "IMPROVE_OWN_ENTRY"
  | "RECALL_LURKERS"
  | "ONBOARD_NEWCOMERS"
  | "ENCOURAGE_POSTING"
  | "OTHER";
export type GrowthMetric =
  | "PV"
  | "OWN_ENTRY_SHARE"
  | "OWN_CTR"
  | "INTERACTION_RATE"
  | "POSTS"
  | "PENETRATION"
  | "ACTIVE_USERS"
  | "ACTIVE_RATE";

export interface GrowthTask {
  id: number;
  title: string;
  taskType: GrowthTaskType;
  entityType: GrowthEntityType;
  entityId: number | null;
  entityName: string | null;
  metric: GrowthMetric;
  baselineValue: number | null;
  targetValue: number;
  ownerName: string | null;
  startDate: string;
  dueDate: string | null;
  status: GrowthTaskStatus;
  note: string | null;
  alertId: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface GrowthTaskView {
  task: GrowthTask;
  currentValue: number | null;
  progress: number | null;
}

export interface GrowthTaskList {
  total: number;
  limit: number;
  offset: number;
  asOf: string;
  tasks: GrowthTaskView[];
}

export interface GrowthEffectPoint {
  windowEnd: string;
  value: number | null;
  containsStart: boolean;
}

export interface GrowthTaskDetail {
  view: GrowthTaskView;
  asOf: string;
  series: GrowthEffectPoint[];
}

export interface GrowthCreateTaskBody {
  title: string;
  taskType?: GrowthTaskType;
  entityType: GrowthEntityType;
  entityId?: number;
  entityName?: string;
  metric: GrowthMetric;
  targetValue: number;
  owner?: string;
  startDate?: string;
  dueDate?: string;
  note?: string;
  alertId?: number;
}

export interface GrowthUpdateTaskBody {
  title?: string;
  owner?: string;
  status?: GrowthTaskStatus;
  targetValue?: number;
  dueDate?: string;
  note?: string;
}
