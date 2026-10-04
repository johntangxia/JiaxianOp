import { describe, expect, it } from "vitest";
import { formatNumber, formatPeriod, formatRate, rateClass } from "./format";

describe("format", () => {
  it("formats numbers as rounded integers with thousands separators", () => {
    expect(formatNumber(1234567.4)).toBe("1,234,567");
    expect(formatNumber(0)).toBe("0");
    expect(formatNumber(999.5)).toBe("1,000");
  });

  it("formats rates as signed percentages and shows a dash when missing", () => {
    expect(formatRate(0.05)).toBe("+5.0%");
    expect(formatRate(-0.123)).toBe("-12.3%");
    expect(formatRate(0)).toBe("0.0%");
    expect(formatRate(null)).toBe("—");
    expect(formatRate(undefined)).toBe("—");
  });

  it("labels periods by granularity", () => {
    expect(formatPeriod("2026-03-05", "DAY")).toBe("2026-03-05");
    expect(formatPeriod("2026-03-02", "WEEK")).toBe("2026-03-02 周");
    expect(formatPeriod("2026-03-01", "MONTH")).toBe("2026-03");
    expect(formatPeriod("2026-01-01", "YEAR")).toBe("2026");
  });

  it("classifies the direction of a rate", () => {
    expect(rateClass(0.1)).toBe("rate-up");
    expect(rateClass(-0.1)).toBe("rate-down");
    expect(rateClass(0)).toBe("rate-none");
    expect(rateClass(null)).toBe("rate-none");
  });
});
