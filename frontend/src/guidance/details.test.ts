import { describe, expect, it } from "vitest";
import { entries, getGuidance } from "./catalog";
import { getGuidanceDetail } from "./details";

describe("完整的看板解读", () => {
  it("每个说明都有阅读方法、具体分析步骤和独立的价值分析", () => {
    for (const entry of entries) {
      const detail = getGuidanceDetail(entry.id);
      expect(detail.reading.length, entry.id).toBeGreaterThanOrEqual(2);
      expect(detail.analysis.length, entry.id).toBeGreaterThanOrEqual(3);
      expect(detail.value.length, entry.id).toBeGreaterThanOrEqual(2);
      expect([...detail.reading, ...detail.analysis, ...detail.value].every(s => s.trim().length > 0), entry.id).toBe(true);
    }
  });
  it("缺失条目不能悄悄回退到其他口径", () => {
    expect(() => getGuidanceDetail("unknown")).toThrow();
  });
  it("相关说明都指向存在的其他条目", () => {
    for (const entry of entries) {
      for (const related of getGuidanceDetail(entry.id).related ?? []) {
        expect(related).not.toBe(entry.id);
        expect(() => getGuidance(related), `${entry.id} -> ${related}`).not.toThrow();
      }
    }
  });
  it("关键板块说明覆盖真实的阅读关系和时间约束", () => {
    expect(getGuidanceDetail("metric.transitions").reading.join(" ")).toContain("行");
    expect(getGuidanceDetail("page.readers.funnel").reading.join(" ")).toContain("独立");
    expect(getGuidanceDetail("page.kol").analysis.join(" ")).toContain("预警");
    expect(getGuidanceDetail("metric.interaction").analysis.join(" ")).toContain("PV");
  });
});
