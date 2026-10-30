import { describe, expect, it } from "vitest";
import { createGuidanceController } from "./context";

describe("说明抽屉状态", () => {
  it("打开、替换并关闭时不保留旧上下文", () => {
    const c = createGuidanceController();
    c.open("metric.entryCtr", { example: { subject: "甲", from: "2026-01-01", to: "2026-01-31", numerator: 1, denominator: 2, numeratorLabel: "点击", denominatorLabel: "曝光" } });
    expect(c.state.id).toBe("metric.entryCtr");
    c.open("metric.contentCtr");
    expect(c.state.id).toBe("metric.contentCtr");
    expect(c.state.context).toBeNull();
    c.close();
    expect(c.state.id).toBeNull();
  });
  it("切换标签更新页面说明并关闭指标说明，切换路由清空", () => {
    const c = createGuidanceController();
    c.setTab("users", "lifecycle");
    c.open("page.users.lifecycle");
    c.setTab("users", "value");
    expect(c.state.id).toBe("page.users.value");
    c.open("metric.userRfm");
    c.setTab("users", "interests");
    expect(c.state.id).toBeNull();
    c.open("page.users.interests");
    c.routeChanged();
    expect(c.state.id).toBeNull();
    expect(c.state.tab).toBeNull();
  });
  it("图表说明即使复用页面内容，在切换标签时仍关闭", () => {
    const c = createGuidanceController();
    c.setTab("readers", "retention");
    c.open("page.readers.retention", undefined, undefined, false);
    c.setTab("readers", "funnel");
    expect(c.state.id).toBeNull();
  });
  it("拒绝未知说明", () => {
    const c = createGuidanceController();
    expect(() => c.open("missing")).toThrow();
    expect(c.state.id).toBeNull();
  });
});
