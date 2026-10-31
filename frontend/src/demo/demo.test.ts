import { describe, expect, it } from "vitest";
import { demoData, defaultDemoCompareIds, findChannel, filterKol, getContestBoard } from "./data";
import { demoRoutes } from "./routes";

describe("public static demo", () => {
  it("contains bounded, anonymous snapshot data", () => {
    expect(demoData.snapshotDate).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(demoData.pv.points.length).toBeGreaterThan(6);
    expect(demoData.cockpit.domain.length).toBe(23);
    expect(demoData.kol.items.length).toBeLessThanOrEqual(60);
    expect(demoData.kol.items.every((item) => /^演示博主 \d+$/.test(item.name))).toBe(true);
    expect(demoData.top.ACCOUNT.every((item) => /^演示账号 \d+$/.test(item.name))).toBe(true);
    expect(demoData.top.EMPLOYEE.every((item) => /^演示博主 \d+$/.test(item.name))).toBe(true);
    expect(demoData.users.length).toBeGreaterThan(0);
    expect(demoData.users.every((item) => /^演示用户 \d+$/.test(item.basic.name))).toBe(true);
    expect(demoData.users.every((item) => item.recentEvents.every((event) => event.refId === null))).toBe(true);
  });

  it("supports only meaningful local selections", () => {
    const first = demoData.cockpit.domain[0];
    expect(findChannel(first.id)?.name).toBe(first.name);
    expect(findChannel(-1)).toBeUndefined();
    expect(getContestBoard("MONTH").type).toBe("MONTH");
    expect(getContestBoard("WEEK").type).toBe("WEEK");
    expect(defaultDemoCompareIds()).toHaveLength(2);
    expect(defaultDemoCompareIds().every((id) => findChannel(id)?.category === "领域频道")).toBe(true);
    const filtered = filterKol({ segment: "CHURN_RISK", alertOnly: true });
    expect(filtered.length).toBeGreaterThan(0);
    expect(filtered.every((item) => item.segment === "CHURN_RISK" && item.churnAlert)).toBe(true);
  });

  it("exposes user 360 and growth tasks but no admin routes", () => {
    const paths = demoRoutes.map((route) => route.path);
    expect(paths).toContain("/channels/:id");
    expect(paths).toContain("/kol/:id");
    expect(paths).toContain("/users/:id");
    expect(paths).toContain("/growth");
    expect(paths).toContain("/accounts/:id");
    expect(paths).toContain("/bloggers/:id");
    expect(paths).toContain("/contents/:id");
    expect(paths).toContain("/compare");
    expect(paths).toContain("/leaderboard");
    expect(paths.some((path) => path.startsWith("/admin"))).toBe(false);
  });
});
