import { describe, expect, it } from "vitest";
import {
  defaultRange,
  parseIsoDate,
  pickerType,
  pickerValue,
  rangeFromPicker,
  toIsoDate,
} from "./dateRange";

const DATA = { from: "2025-01-01", to: "2026-03-27" };

describe("iso dates", () => {
  it("round-trips local dates without timezone shifts", () => {
    expect(toIsoDate(new Date(2026, 2, 5))).toBe("2026-03-05");
    expect(toIsoDate(parseIsoDate("2026-12-31"))).toBe("2026-12-31");
  });
});

describe("defaultRange", () => {
  it("ends at the last data day and goes back by granularity", () => {
    expect(defaultRange("DAY", DATA)).toEqual({ from: "2026-02-26", to: "2026-03-27" });      // 30 天
    expect(defaultRange("WEEK", DATA)).toEqual({ from: "2025-10-03", to: "2026-03-27" });     // 26 周
    expect(defaultRange("MONTH", DATA)).toEqual({ from: "2025-04-01", to: "2026-03-27" });    // 12 个月
    // 年：5 年 = 2022-01-01，但数据从 2025-01-01 才开始，所以被截断
    expect(defaultRange("YEAR", DATA)).toEqual({ from: "2025-01-01", to: "2026-03-27" });
    expect(defaultRange("YEAR", { from: "2020-01-01", to: "2026-03-27" }))
      .toEqual({ from: "2022-01-01", to: "2026-03-27" });
  });

  it("never starts before the first day with data", () => {
    expect(defaultRange("MONTH", { from: "2025-09-15", to: "2026-03-27" }))
      .toEqual({ from: "2025-09-15", to: "2026-03-27" });
  });

  it("falls back to today when there is no data range", () => {
    expect(defaultRange("MONTH", null, new Date(2026, 9, 4))).toEqual({ from: "2025-11-01", to: "2026-10-04" });
    expect(defaultRange("DAY", { from: null, to: null }, new Date(2026, 9, 4)))
      .toEqual({ from: "2026-09-05", to: "2026-10-04" });
  });
});

describe("date picker conversion", () => {
  it("uses the matching picker type per granularity", () => {
    expect(pickerType("DAY")).toBe("daterange");
    expect(pickerType("WEEK")).toBe("daterange");
    expect(pickerType("MONTH")).toBe("monthrange");
    expect(pickerType("YEAR")).toBe("yearrange");
  });

  it("expands month and year picks to whole periods", () => {
    expect(rangeFromPicker("MONTH", [new Date(2025, 2, 15), new Date(2026, 1, 10)]))
      .toEqual({ from: "2025-03-01", to: "2026-02-28" });
    expect(rangeFromPicker("MONTH", [new Date(2024, 0, 1), new Date(2024, 1, 1)]))
      .toEqual({ from: "2024-01-01", to: "2024-02-29" });
    expect(rangeFromPicker("YEAR", [new Date(2022, 6, 1), new Date(2025, 1, 2)]))
      .toEqual({ from: "2022-01-01", to: "2025-12-31" });
    expect(rangeFromPicker("DAY", [new Date(2026, 2, 1), new Date(2026, 2, 9)]))
      .toEqual({ from: "2026-03-01", to: "2026-03-09" });
  });

  it("turns a range back into picker dates", () => {
    const [a, b] = pickerValue("DAY", { from: "2026-03-01", to: "2026-03-09" });
    expect(toIsoDate(a)).toBe("2026-03-01");
    expect(toIsoDate(b)).toBe("2026-03-09");
  });
});
