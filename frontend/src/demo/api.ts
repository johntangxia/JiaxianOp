import { demoData } from "./data";
import { addDemoTask, deleteDemoTask, readDemoTasks, updateDemoTask } from "./tasks";
import type { QueryParams } from "../api/query";
import type { GrowthAlert, GrowthCreateTaskBody, GrowthTaskView, GrowthUpdateTaskBody, Segment } from "../api/types";

// The fixture is generated from a simulated local stack. No browser request is
// made for any API path in this module, including unsupported paths.
async function loadFixtures(path: string, params: QueryParams = {}): Promise<Record<string, any>> {
  if (path === "/api/compare") {
    if (params.type === "ACCOUNT") return (await import("./fixtures/accounts.json")).default;
    if (params.type === "BLOGGER") return (await import("./fixtures/bloggers.json")).default;
    return (await import("./fixtures/channels.json")).default;
  }
  if (path.startsWith("/api/analytics/") || path.startsWith("/api/dimensions/")) return (await import("./fixtures/core.json")).default;
  if (path.startsWith("/api/channels/") || path.startsWith("/api/drill/channels/")) return (await import("./fixtures/channels.json")).default;
  if (path.startsWith("/api/drill/accounts/")) return (await import("./fixtures/accounts.json")).default;
  if (path.startsWith("/api/drill/bloggers/")) return (await import("./fixtures/bloggers.json")).default;
  if (path.startsWith("/api/drill/contents/")) return (await import("./fixtures/contents.json")).default;
  if (path.startsWith("/api/readers/")) return (await import("./fixtures/readers.json")).default;
  if (path.startsWith("/api/creators/")) return (await import("./fixtures/creators.json")).default;
  if (path.startsWith("/api/users/")) return (await import("./fixtures/users.json")).default;
  if (path.startsWith("/api/growth/")) return (await import("./fixtures/growth.json")).default;
  if (path.startsWith("/api/leaderboard/")) return (await import("./fixtures/leaderboard.json")).default;
  if (path.startsWith("/api/bloggers/")) return (await import("./fixtures/kol.json")).default;
  return {};
}
const copy = <T>(value: unknown): T => structuredClone(value) as T;
const invalid = (message: string): never => { throw new Error(`静态演示：${message}`); };
const number = (value: QueryParams[string], fallback: number): number => value === undefined || value === null || value === "" ? fallback : Number(value);
const text = (value: QueryParams[string]): string | undefined => value === undefined || value === null || value === "" ? undefined : String(value);
const page = <T>(items: T[], params: QueryParams, defaultLimit = 20): T[] => items.slice(Math.max(0, number(params.offset, 0)), Math.max(0, number(params.offset, 0)) + Math.max(0, number(params.limit, defaultLimit)));

function windowMatches(params: QueryParams, from: string, to: string): void {
  const requestedFrom = text(params.from);
  const requestedTo = text(params.to);
  if ((requestedFrom && requestedFrom !== from) || (requestedTo && requestedTo !== to)) {
    invalid(`此看板只提供 ${from} 至 ${to} 的模拟快照，请选回该日期范围。`);
  }
}

function asOfMatches(params: QueryParams): void {
  const requested = text(params.asOf);
  if (requested && requested !== demoData.snapshotDate) invalid(`此看板只提供 ${demoData.snapshotDate} 的模拟快照。`);
}

/** Scenario scaling for combined filters; the public sample has no raw events. */
function overviewFactor(params: QueryParams, granularity: string, fx: Record<string, any>): number {
  const top = fx.topByGranularity?.[granularity] ?? demoData.top;
  const trend = fx.pvTrends?.[granularity] ?? demoData.pv;
  const total = trend.points.reduce((sum: number, point: { pv: number }) => sum + point.pv, 0) || 1;
  let factor = 1;
  const selectors: Array<[string, string, string]> = [
    ["channelId", "CHANNEL", "id"], ["accountId", "ACCOUNT", "id"], ["employeeId", "EMPLOYEE", "id"], ["contentType", "CONTENT_TYPE", "name"],
  ];
  for (const [param, dimension, field] of selectors) {
    if (!params[param]) continue;
    const item = (top[dimension] as Array<Record<string, unknown>>).find((row) => String(row[field]) === String(params[param]));
    factor *= item ? Number(item.pv) / total : 0.025;
  }
  return Math.max(0.0001, Math.min(1, factor));
}

function scaledTrend(trend: any, factor: number) {
  if (factor === 1) return trend;
  return {
    ...trend,
    points: trend.points.map((point: any) => ({ ...point, pv: Math.round(point.pv * factor) })),
    forecast: trend.forecast.map((point: any) => ({ ...point, value: point.value * factor, lower: point.lower * factor, upper: point.upper * factor })),
  };
}

function demoUsers(params: QueryParams) {
  const rows = demoData.users.map((profile) => ({
    employeeId: profile.basic.employeeId,
    name: profile.basic.name,
    dept: profile.basic.dept,
    joinDate: profile.basic.joinDate,
    stage: profile.basic.stage,
    stageLabel: profile.basic.stageLabel,
    valueSegment: profile.basic.valueSegment,
    valueLabel: profile.basic.valueLabel,
    lastActive: profile.recent.lastActive,
    activeDays: profile.recent.activeDays,
    views: profile.recent.views,
    interactions: profile.recent.interactions,
    posts: profile.recent.posts,
  }));
  const q = (text(params.q) ?? "").toLowerCase();
  const filtered = rows.filter((row) =>
    (!params.stage || row.stage === params.stage) &&
    (!params.value || row.valueSegment === params.value) &&
    (!params.dept || row.dept === params.dept) &&
    (!q || `${row.name} ${row.dept ?? ""}`.toLowerCase().includes(q)),
  );
  const sortField: Record<string, keyof typeof rows[number]> = { VIEWS: "views", INTERACTIONS: "interactions", POSTS: "posts", ACTIVE_DAYS: "activeDays", LAST_ACTIVE: "lastActive" };
  const key = sortField[text(params.sort) ?? "LAST_ACTIVE"] ?? "lastActive";
  filtered.sort((a, b) => String(b[key] ?? "").localeCompare(String(a[key] ?? ""), "zh-CN", { numeric: true }));
  return { asOf: demoData.snapshotDate, total: filtered.length, limit: number(params.limit, 20), offset: number(params.offset, 0), users: page(filtered, params), depts: [...new Set(rows.map((row) => row.dept).filter((item): item is string => !!item))].sort() };
}

function taskView(task: ReturnType<typeof readDemoTasks>[number]): GrowthTaskView {
  const base = task.baselineValue;
  const current = task.currentValue;
  const denominator = base === null ? 0 : task.targetValue - base;
  const progress = base === null || current === null || denominator === 0 ? null : (current - base) / denominator;
  const { currentValue: _, ...plain } = task;
  return { task: plain, currentValue: current, progress };
}

function readAlerts(fx: Record<string, any>): GrowthAlert[] {
  const original = fx.growth.alerts.alerts as GrowthAlert[];
  if (typeof window === "undefined") return copy(original);
  try {
    const raw = window.localStorage?.getItem("jiaxian-static-demo-alerts-v1");
    if (raw) {
      const statuses = JSON.parse(raw) as Record<string, GrowthAlert["status"]>;
      return original.map((item) => ({ ...item, status: statuses[item.id] ?? item.status }));
    }
  } catch { /* no browser storage in tests */ }
  return copy(original);
}

function saveAlertStatus(fx: Record<string, any>, id: number, status: GrowthAlert["status"]): GrowthAlert {
  const alerts = readAlerts(fx);
  const alert = alerts.find((item) => item.id === id);
  if (!alert) throw new Error("静态演示：告警不存在");
  alert.status = status;
  try { if (typeof window !== "undefined") window.localStorage?.setItem("jiaxian-static-demo-alerts-v1", JSON.stringify(Object.fromEntries(alerts.map((item) => [item.id, item.status])))); } catch { /* memoryless preview */ }
  return alert;
}

export async function demoGetJson<T>(path: string, params: QueryParams = {}): Promise<T> {
  const fx = await loadFixtures(path, params);
  const range = demoData.cockpit;
  const detailId = (regex: RegExp) => path.match(regex)?.[1];
  let value: unknown;

  if (path === "/api/meta/pv-range" || path === "/api/readers/range") value = { from: "2025-04-01", to: demoData.snapshotDate };
  else if (path === "/api/dimensions/channels") value = fx.dimensions.channels;
  else if (path === "/api/dimensions/accounts" || path === "/api/dimensions/employees") {
    const options = path.endsWith("accounts") ? fx.dimensions.accounts : fx.dimensions.employees;
    const keyword = (text(params.keyword) ?? "").toLowerCase();
    value = options.filter((item: { name: string }) => item.name.toLowerCase().includes(keyword)).slice(0, number(params.limit, 20));
  } else if (path === "/api/analytics/pv/trend") {
    const trend = fx.pvTrends?.[text(params.granularity) ?? "MONTH"] ?? demoData.pv;
    windowMatches(params, trend.from, trend.to);
    value = scaledTrend(trend, overviewFactor(params, text(params.granularity) ?? "MONTH", fx));
  } else if (path === "/api/analytics/pv/top") {
    const granularity = Object.keys(fx.pvTrends ?? {}).find((key) => fx.pvTrends[key].from === params.from && fx.pvTrends[key].to === params.to) ?? "MONTH";
    windowMatches(params, fx.pvTrends?.[granularity]?.from ?? demoData.pv.from, demoData.pv.to);
    const dimension = text(params.dimension) ?? "CHANNEL";
    let rows = (fx.topByGranularity?.[granularity]?.[dimension] ?? demoData.top[dimension as keyof typeof demoData.top]) as Array<{ id: number | null; name: string; pv: number }>;
    const matchParam: Record<string, string> = { CHANNEL: "channelId", ACCOUNT: "accountId", EMPLOYEE: "employeeId", CONTENT_TYPE: "contentType" };
    const selected = params[matchParam[dimension]];
    if (selected) {
      rows = rows.filter((row) => String(dimension === "CONTENT_TYPE" ? row.name : row.id) === String(selected));
      if (rows.length === 0 && dimension !== "CONTENT_TYPE") {
        const options = dimension === "CHANNEL" ? fx.dimensions.channels : dimension === "ACCOUNT" ? fx.dimensions.accounts : fx.dimensions.employees;
        const option = options.find((item: { id: number }) => String(item.id) === String(selected));
        const total = (fx.pvTrends?.[granularity] ?? demoData.pv).points.reduce((sum: number, point: { pv: number }) => sum + point.pv, 0);
        if (option) rows = [{ id: Number(selected), name: option.name, pv: Math.round(total * overviewFactor({ [matchParam[dimension]]: selected }, granularity, fx)) }];
      }
    }
    const factor = overviewFactor({ ...params, [matchParam[dimension]]: undefined }, granularity, fx);
    value = rows.slice(0, number(params.limit, 10)).map((row) => ({ ...row, pv: Math.round(row.pv * factor) }));
  } else if (path === "/api/channels/cockpit") {
    windowMatches(params, range.from, range.to);
    value = range;
  } else if (detailId(/^\/api\/channels\/(\d+)\/detail$/)) {
    const id = detailId(/^\/api\/channels\/(\d+)\/detail$/)!;
    const granularity = text(params.granularity) ?? "WEEK";
    if (params.from === range.from && params.to === range.to) value = fx.channelDetailsByGranularity?.[id]?.[granularity] ?? fx.channelDetails[id];
    else value = fx.contestChannelDetails?.[`${params.from}|${params.to}|${id}|${granularity}`];
  } else if (detailId(/^\/api\/drill\/channels\/(\d+)\/accounts$/)) {
    value = fx.channelAccounts[detailId(/^\/api\/drill\/channels\/(\d+)\/accounts$/)!];
  } else if (detailId(/^\/api\/drill\/accounts\/(\d+)$/)) {
    windowMatches(params, range.from, range.to);
    const id = detailId(/^\/api\/drill\/accounts\/(\d+)$/)!;
    value = fx.accountsByGranularity?.[id]?.[text(params.granularity) ?? "WEEK"] ?? fx.accounts[id];
  } else if (detailId(/^\/api\/drill\/bloggers\/(\d+)$/)) {
    windowMatches(params, range.from, range.to);
    const id = detailId(/^\/api\/drill\/bloggers\/(\d+)$/)!;
    value = fx.bloggersByGranularity?.[id]?.[text(params.granularity) ?? "WEEK"] ?? fx.bloggers[id];
  } else if (detailId(/^\/api\/drill\/contents\/(\d+)$/)) {
    const id = detailId(/^\/api\/drill\/contents\/(\d+)$/)!;
    const granularity = text(params.granularity) ?? "WEEK";
    if (params.from === range.from && params.to === range.to) value = fx.contentsByGranularity?.[id]?.[granularity] ?? fx.contents[id];
    else value = fx.hotContentsByGranularity?.[id]?.[granularity];
  } else if (path === "/api/compare") {
    windowMatches(params, range.from, range.to);
    const type = text(params.type) ?? "CHANNEL";
    const ids = (text(params.ids) ?? "").split(",").map(Number);
    const granularity = text(params.granularity) ?? "WEEK";
    const subjects = ids.map((id) => {
      const details = type === "CHANNEL" ? fx.channelDetailsByGranularity?.[id]?.[granularity] ?? fx.channelDetails[id] : type === "ACCOUNT" ? fx.accountsByGranularity?.[id]?.[granularity] ?? fx.accounts[id] : fx.bloggersByGranularity?.[id]?.[granularity] ?? fx.bloggers[id];
      const source = type === "CHANNEL" ? details?.row : details;
      if (!source) invalid(`对比对象 ${id} 不在公开样本中。`);
      if (type === "CHANNEL") {
        const metrics = (period: any) => ({ content: period.content ?? { pv: 0, uv: 0, interactions: 0, interactionRate: null, top1Share: null, top3Share: null, sourceMix: { ownEntry: 0, compositeEntry: 0, search: 0, share: 0, other: 0 }, ownEntryShare: null, compositeDependency: null }, supply: period.supply ?? { contents: 0, authors: 0 }, exposure: { ...period.distribution, completionRate: null }, followers: null });
        return { id, name: source.name, extra: source.category, current: metrics(source.current), previous: metrics(source.previous), ownEntry: source.current.distribution, trend: details.trend.map((point: object) => ({ ...point, newFollowers: 0, lostFollowers: 0 })) };
      }
      return { id, name: source.account?.name ?? source.blogger?.name, extra: source.account?.type ?? source.blogger?.dept ?? null, current: source.current, previous: source.previous, ownEntry: null, trend: source.trend };
    });
    value = { type, from: range.from, to: range.to, previousFrom: range.previousFrom, previousTo: range.previousTo, granularity, subjects };
  } else if (path === "/api/contest/periods") {
    const kind = (text(params.type) ?? "MONTH") as "WEEK" | "MONTH";
    const board = demoData.boards[kind];
    value = [{ periodStart: board.periodStart, periodEnd: board.periodEnd, complete: true }];
  } else if (path === "/api/contest/boards") {
    const kind = (text(params.type) ?? "MONTH") as "WEEK" | "MONTH";
    value = demoData.boards[kind];
  } else if (path === "/api/readers/activity") {
    windowMatches(params, range.from, range.to);
    value = fx.readerActivity[text(params.granularity) ?? "WEEK"];
  } else if (path === "/api/readers/retention") {
    windowMatches(params, range.from, range.to);
    value = fx.readerRetention[text(params.granularity) ?? "WEEK"];
  } else if (path === "/api/readers/funnel") {
    windowMatches(params, range.from, range.to);
    value = fx.readerFunnel;
  } else if (path === "/api/readers/segments") {
    windowMatches(params, range.from, range.to);
    value = fx.readerSegments;
  } else if (path === "/api/readers/lurkers") {
    windowMatches(params, range.from, range.to);
    const rows = fx.readerLurkers.lurkers.filter((item: { dept: string }) => !params.dept || item.dept === params.dept);
    value = { ...fx.readerLurkers, total: rows.length, limit: number(params.limit, 20), offset: number(params.offset, 0), lurkers: page(rows, params) };
  } else if (path === "/api/creators/overview") {
    windowMatches(params, range.from, range.to);
    value = fx.creatorOverview[text(params.granularity) ?? "MONTH"];
  } else if (path === "/api/creators/ranking") {
    windowMatches(params, range.from, range.to);
    const sort = text(params.sort) ?? "POSTS";
    const raw = fx.creatorRanking[sort];
    const rows = raw.creators.filter((item: { dept: string }) => !params.dept || item.dept === params.dept);
    value = { ...raw, total: rows.length, limit: number(params.limit, 20), offset: number(params.offset, 0), creators: page(rows, params) };
  } else if (path === "/api/creators/ladder") {
    asOfMatches(params); value = fx.creatorLadder;
  } else if (path === "/api/creators/concentration") {
    windowMatches(params, range.from, range.to); value = fx.creatorConcentration;
  } else if (path === "/api/users/lifecycle") { asOfMatches(params); value = fx.users.lifecycle; }
  else if (path === "/api/users/value") { asOfMatches(params); value = fx.users.value; }
  else if (path === "/api/users/interests") { asOfMatches(params); value = fx.users.interests; }
  else if (path === "/api/users/list") { asOfMatches(params); value = demoUsers(params); }
  else if (detailId(/^\/api\/users\/(\d+)$/)) {
    asOfMatches(params);
    const id = detailId(/^\/api\/users\/(\d+)$/)!;
    value = demoData.users.find((item) => item.basic.employeeId === Number(id)) ?? fx.users.profiles?.[id];
  } else if (path === "/api/growth/departments") { asOfMatches(params); value = fx.growth.departments; }
  else if (path === "/api/growth/hot-contents") { asOfMatches(params); value = fx.growth.hot; }
  else if (path === "/api/growth/alerts") {
    const alerts = readAlerts(fx);
    const rows = alerts.filter((item) => (!params.status || item.status === params.status) && (!params.entityType || item.entityType === params.entityType));
    value = { total: rows.length, limit: number(params.limit, 20), offset: number(params.offset, 0), counts: { OPEN: alerts.filter((a) => a.status === "OPEN").length, ACKED: alerts.filter((a) => a.status === "ACKED").length, CLOSED: alerts.filter((a) => a.status === "CLOSED").length }, alerts: page(rows, params) };
  } else if (path === "/api/growth/tasks") {
    const tasks = readDemoTasks().filter((item) => !params.status || item.status === params.status).map(taskView);
    value = { total: tasks.length, limit: number(params.limit, 20), offset: number(params.offset, 0), asOf: demoData.snapshotDate, tasks: page(tasks, params) };
  } else if (detailId(/^\/api\/growth\/tasks\/(\d+)$/)) {
    const task = readDemoTasks().find((item) => item.id === Number(detailId(/^\/api\/growth\/tasks\/(\d+)$/)));
    if (task) value = { view: taskView(task), asOf: demoData.snapshotDate, series: [{ windowEnd: demoData.snapshotDate, value: task.currentValue, containsStart: true }] };
  } else if (detailId(/^\/api\/leaderboard\/(month|year)\/periods$/)) {
    value = fx.leaderboard[detailId(/^\/api\/leaderboard\/(month|year)\/periods$/)!].periods;
  } else if (detailId(/^\/api\/leaderboard\/(month|year)$/)) {
    const kind = detailId(/^\/api\/leaderboard\/(month|year)$/)!;
    const period = text(params.period) ?? fx.leaderboard[kind].periods[0].periodValue;
    const board = fx.leaderboard[kind].boards[period];
    value = board ? { ...board, entries: board.entries.slice(0, number(params.limit, 20)) } : undefined;
  } else if (path === "/api/bloggers/runs") value = [demoData.kol.asOf];
  else if (path === "/api/bloggers/top1000") {
    const items = demoData.kol.items.filter((item) => (!params.segment || item.segment === params.segment) && (!params.alert || item.churnAlert));
    const all = demoData.kol.items;
    value = { asOf: demoData.kol.asOf, total: items.length, segmentCounts: Object.fromEntries(["STAR_KOL", "STEADY", "RISING", "CHURN_RISK", "DORMANT", "OBSERVE"].map((segment) => [segment, all.filter((item) => item.segment === segment).length])) as Record<Segment, number>, items: page(items, params) };
  } else if (detailId(/^\/api\/bloggers\/(\d+)\/profile$/)) value = fx.kolProfiles[detailId(/^\/api\/bloggers\/(\d+)\/profile$/)!];
  else if (detailId(/^\/api\/bloggers\/(\d+)\/activity$/)) {
    const id = detailId(/^\/api\/bloggers\/(\d+)\/activity$/)!;
    value = fx.kolActivityByGranularity?.[id]?.[text(params.granularity) ?? "WEEK"] ?? fx.kolActivity[id];
  } else if (detailId(/^\/api\/bloggers\/(\d+)\/points-trend$/)) {
    const id = detailId(/^\/api\/bloggers\/(\d+)\/points-trend$/)!;
    value = fx.kolPointsByGranularity?.[id]?.[text(params.granularity) ?? "WEEK"] ?? fx.kolPoints[id];
  }

  if (value === undefined) invalid(`没有 ${path} 的模拟样本。`);
  return copy<T>(value);
}

export async function demoSendJson<T>(method: "POST" | "PUT" | "DELETE", path: string, options: { params?: QueryParams; body?: unknown } = {}): Promise<T> {
  const fx = path.startsWith("/api/admin/growth/alerts") ? await loadFixtures("/api/growth/alerts") : {};
  let value: unknown;
  if (method === "POST" && path === "/api/admin/growth/tasks") value = taskView(addDemoTask(undefined, options.body as GrowthCreateTaskBody));
  else if (method === "PUT" && /^\/api\/admin\/growth\/tasks\/\d+$/.test(path)) {
    const task = updateDemoTask(undefined, Number(path.split("/").pop()), options.body as GrowthUpdateTaskBody);
    value = task && taskView(task);
  } else if (method === "DELETE" && /^\/api\/admin\/growth\/tasks\/\d+$/.test(path)) {
    deleteDemoTask(undefined, Number(path.split("/").pop())); value = null;
  } else if (method === "PUT" && /^\/api\/admin\/growth\/alerts\/\d+\/status$/.test(path)) {
    value = saveAlertStatus(fx, Number(path.split("/")[5]), (options.body as { status: GrowthAlert["status"] }).status);
  } else if (method === "POST" && path === "/api/admin/growth/alerts/rebuild") {
    try { if (typeof window !== "undefined") window.localStorage?.removeItem("jiaxian-static-demo-alerts-v1"); } catch { /* memoryless preview */ }
    value = { asOf: demoData.snapshotDate, created: 0, updated: 0, total: readAlerts(fx).length };
  }
  if (value === undefined) invalid(`此操作 ${method} ${path} 不在公开演示中。`);
  return copy<T>(value);
}
