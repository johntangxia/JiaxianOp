import { describe, expect, it } from "vitest";
import { entries, getGuidance, getPageGuidance } from "./catalog";

describe("说明口径", () => {
  it("区分入口和内容点击率，以及两套 RFM", () => {
    expect(getGuidance("metric.entryCtr").meaning).toContain("入口");
    expect(getGuidance("metric.contentCtr").meaning).toContain("内容");
    expect(getGuidance("metric.kolRfm").calculation).toContain("积分");
    expect(getGuidance("metric.userRfm").calculation).toContain("活跃天数");
  });
  it("对未知页面返回空，对标签页返回对应说明", () => {
    expect(getPageGuidance("unknown")).toBeNull();
    expect(getPageGuidance("visit-flow")).toBe("page.visit-flow");
    expect(getPageGuidance("readers", "funnel")).toBe("page.readers.funnel");
    expect(getPageGuidance("users", "value")).toBe("page.users.value");
  });
});

describe("看板入口覆盖", () => {
  it("说明 ID 唯一，避免不同口径被覆盖", () => {
    expect(new Set(entries.map(item => item.id)).size).toBe(entries.length);
  });
  it("每条页面和标签映射到自己的完整说明", () => {
    const routes: Array<[string, string | undefined]> = [
      ["overview", undefined], ["channels", undefined], ["channel-detail", undefined], ["contest", undefined], ["compare", undefined],
      ["account-detail", undefined], ["blogger-detail", undefined], ["content-detail", undefined], ["user-detail", undefined],
      ["leaderboard", undefined], ["kol", undefined], ["kol-detail", undefined], ["simulator", undefined], ["points-config", undefined], ["playbook", undefined],
      ...["activity", "retention", "funnel", "segments"].map(tab => ["readers", tab] as [string, string]),
      ...["overview", "ranking", "ladder", "concentration"].map(tab => ["creators", tab] as [string, string]),
      ...["lifecycle", "value", "interests", "list"].map(tab => ["users", tab] as [string, string]),
      ...["alerts", "tasks", "hot", "departments"].map(tab => ["growth", tab] as [string, string]),
    ];
    for (const [route, tab] of routes) {
      const id = getPageGuidance(route, tab);
      expect(id, `${route}/${tab}`).toBeTruthy();
      const e = getGuidance(id!);
      expect([e.title, e.meaning, e.calculation, e.analysis, e.caution, e.source].every(Boolean)).toBe(true);
    }
  });
});
