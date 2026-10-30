import { describe, expect, it } from "vitest";
import { ratioExample } from "./examples";
const scope = { subject: "AI学习", from: "2026-05-01", to: "2026-10-27" };
const base = { ...scope, numerator: 142, denominator: 4909, numeratorLabel: "互动", denominatorLabel: "PV" };
describe("当前数据示例", () => {
  it("使用真实分子分母并按页面精度显示", () => {
    expect(ratioExample(base, scope)).toContain("2.9%");
    expect(ratioExample({ ...base, numerator: 120, denominator: 2085 }, scope)).toContain("5.8%");
  });
  it("数值、日期或对象不匹配时不生成示例", () => {
    expect(ratioExample({ ...base, denominator: 0 }, scope)).toBeNull();
    expect(ratioExample({ ...base, numerator: NaN }, scope)).toBeNull();
    expect(ratioExample(base, { ...scope, subject: "其他" })).toBeNull();
    expect(ratioExample(base, { ...scope, to: "2026-10-26" })).toBeNull();
  });
});
