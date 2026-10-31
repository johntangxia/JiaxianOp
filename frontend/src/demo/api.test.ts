import { describe, expect, it } from "vitest";
import { demoGetJson, demoSendJson } from "./api";

describe("static demo API", () => {
  it("serves dashboard data without network access", async () => {
    const range = await demoGetJson<{ from: string; to: string }>("/api/meta/pv-range");
    expect(range.to).toBe("2026-10-27");
    const channels = await demoGetJson<Array<{ id: number }>>("/api/dimensions/channels");
    expect(channels.length).toBe(28);
  });

  it("filters and paginates the anonymous user list", async () => {
    const all = await demoGetJson<{ total: number; users: Array<{ stage: string }> }>("/api/users/list", { sort: "VIEWS", limit: 2, offset: 0 });
    const active = await demoGetJson<{ total: number; users: Array<{ stage: string }> }>("/api/users/list", { stage: "ACTIVE", sort: "VIEWS", limit: 2, offset: 0 });
    expect(all.total).toBeGreaterThan(active.total);
    expect(all.users).toHaveLength(2);
    expect(active.users.every((user) => user.stage === "ACTIVE")).toBe(true);
  });

  it("changes overview values for a selected simulated channel", async () => {
    const query = { granularity: "MONTH", from: "2025-11-01", to: "2026-10-27" };
    const all = await demoGetJson<{ points: Array<{ pv: number }> }>("/api/analytics/pv/trend", query);
    const filtered = await demoGetJson<{ points: Array<{ pv: number }> }>("/api/analytics/pv/trend", { ...query, channelId: 25 });
    expect(filtered.points[0].pv).toBeGreaterThan(0);
    expect(filtered.points[0].pv).toBeLessThan(all.points[0].pv);
  });

  it("keeps growth writes in browser-local state", async () => {
    const created = await demoSendJson<{ task: { id: number; title: string } }>("POST", "/api/admin/growth/tasks", { body: { title: "演示任务", entityType: "CHANNEL", metric: "PV", targetValue: 100 } });
    expect(created.task.title).toBe("演示任务");
    const list = await demoGetJson<{ tasks: Array<{ task: { id: number } }> }>("/api/growth/tasks", { limit: 20, offset: 0 });
    expect(list.tasks.some((task) => task.task.id === created.task.id)).toBe(true);
    await demoSendJson("DELETE", `/api/admin/growth/tasks/${created.task.id}`);
  });

  it("rejects unlisted endpoints instead of reaching a server", async () => {
    await expect(demoGetJson("/api/admin/simulator/realtime/status")).rejects.toThrow(/静态演示/);
  });

  it("provides data for every non-admin page family and linked detail", async () => {
    const interval = { from: "2026-05-01", to: "2026-10-27" };
    const accounts = await demoGetJson<{ accounts: Array<{ id: number }> }>("/api/drill/channels/6/accounts", interval);
    const account = await demoGetJson<{ authors: Array<{ id: number }>; topContents: Array<{ contentId: number }> }>(`/api/drill/accounts/${accounts.accounts[0].id}`, { ...interval, granularity: "WEEK" });
    const urls: Array<[string, Record<string, string | number>]> = [
      ["/api/channels/6/detail", { ...interval, granularity: "WEEK" }],
      [`/api/drill/bloggers/${account.authors[0].id}`, { ...interval, granularity: "WEEK" }],
      [`/api/drill/contents/${account.topContents[0].contentId}`, { ...interval, granularity: "WEEK" }],
      ["/api/compare", { ...interval, granularity: "WEEK", type: "CHANNEL", ids: "6,7" }],
      ["/api/readers/activity", { ...interval, granularity: "MONTH" }],
      ["/api/readers/retention", { ...interval, granularity: "MONTH" }],
      ["/api/readers/funnel", interval],
      ["/api/readers/segments", interval],
      ["/api/readers/lurkers", { ...interval, limit: 20, offset: 0 }],
      ["/api/creators/overview", { ...interval, granularity: "MONTH" }],
      ["/api/creators/ranking", { ...interval, sort: "POSTS", limit: 20, offset: 0 }],
      ["/api/creators/ladder", { asOf: "2026-10-27" }],
      ["/api/creators/concentration", interval],
      ["/api/users/lifecycle", { asOf: "2026-10-27" }],
      ["/api/users/value", { asOf: "2026-10-27" }],
      ["/api/users/interests", { asOf: "2026-10-27", days: 90 }],
      ["/api/growth/departments", { days: 30 }],
      ["/api/growth/hot-contents", { limit: 20 }],
      ["/api/growth/alerts", { limit: 20, offset: 0 }],
      ["/api/growth/tasks", { limit: 20, offset: 0 }],
      ["/api/leaderboard/month/periods", {}],
      ["/api/bloggers/top1000", { limit: 20, offset: 0 }],
      ["/api/bloggers/1/profile", {}],
      ["/api/bloggers/1/activity", { granularity: "WEEK" }],
      ["/api/bloggers/1/points-trend", { granularity: "WEEK" }],
    ];
    for (const [url, params] of urls) {
      expect(await demoGetJson(url, params), url).toBeDefined();
    }
  }, 15_000);

  it("resolves linked silent-reader, hot-content, and contest-period details", async () => {
    const interval = { from: "2026-05-01", to: "2026-10-27" };
    const lurkers = await demoGetJson<{ lurkers: Array<{ employeeId: number }> }>("/api/readers/lurkers", { ...interval, limit: 20, offset: 0 });
    const user = await demoGetJson<{ basic: { employeeId: number } }>(`/api/users/${lurkers.lurkers[0].employeeId}`, { asOf: interval.to });
    expect(user.basic.employeeId).toBe(lurkers.lurkers[0].employeeId);
    const hot = await demoGetJson<{ hot: Array<{ contentId: number }> }>("/api/growth/hot-contents", { limit: 20 });
    const content = await demoGetJson<{ content: { id: number } }>(`/api/drill/contents/${hot.hot[0].contentId}`, { from: "2026-09-28", to: interval.to, granularity: "WEEK" });
    expect(content.content.id).toBe(hot.hot[0].contentId);
    const periods = await demoGetJson<Array<{ periodStart: string; periodEnd: string }>>("/api/contest/periods", { type: "MONTH" });
    const detail = await demoGetJson<{ row: { id: number } }>("/api/channels/6/detail", { from: periods[0].periodStart, to: periods[0].periodEnd, granularity: "WEEK" });
    expect(detail.row.id).toBe(6);
  });
});
