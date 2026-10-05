import { describe, expect, it } from "vitest";
import { cohortLabel, dateOnly, heatCell, isSmallCohort, MIN_COHORT_SIZE, periodHeader, roleColor } from "./readers";

describe("cohort labels", () => {
  it("labels weeks with the Monday and months with year-month", () => {
    expect(cohortLabel("2025-06-02", "WEEK")).toBe("2025-06-02 周");
    expect(cohortLabel("2025-06-01", "MONTH")).toBe("2025-06");
  });

  it("names the columns by the period after joining", () => {
    expect(periodHeader(0, "WEEK")).toBe("加入当周");
    expect(periodHeader(3, "WEEK")).toBe("第 3 周");
    expect(periodHeader(0, "MONTH")).toBe("加入当月");
    expect(periodHeader(2, "MONTH")).toBe("第 2 月");
  });
});

describe("heatCell", () => {
  it("shows a grey dash when the period is not covered by data", () => {
    expect(heatCell(null)).toEqual({ text: "—", background: "#f5f7fa", color: "#9aa3b2" });
    expect(heatCell(undefined).text).toBe("—");
  });

  it("darkens with retention and switches to white text above 60 percent", () => {
    const low = heatCell(0.1);
    const high = heatCell(0.9);

    expect(low.text).toBe("10%");
    expect(low.color).toBe("#1f2937");
    expect(high.text).toBe("90%");
    expect(high.color).toBe("#fff");
    const alpha = (cell: { background: string }) => Number(cell.background.match(/,\s*([\d.]+)\)$/)![1]);
    expect(alpha(high)).toBeGreaterThan(alpha(low));
  });

  it("keeps zero visible and clamps out-of-range values", () => {
    expect(heatCell(0).text).toBe("0%");
    expect(heatCell(0).background).toBe("rgba(47, 111, 237, 0.08)");
    expect(heatCell(1.5).background).toBe("rgba(47, 111, 237, 0.90)");
  });
});

describe("misc helpers", () => {
  it("gives every role its own colour", () => {
    const colours = (["CREATOR", "INTERACTOR", "LURKER", "LOGIN_ONLY", "INACTIVE"] as const).map(roleColor);

    expect(new Set(colours).size).toBe(5);
  });

  it("cuts a timestamp down to the date", () => {
    expect(dateOnly("2025-06-10T08:30:00")).toBe("2025-06-10");
    expect(dateOnly(null)).toBe("—");
  });
});

describe("isSmallCohort", () => {
  it("flags cohorts with fewer than five people", () => {
    expect(MIN_COHORT_SIZE).toBe(5);
    expect(isSmallCohort(0)).toBe(true);
    expect(isSmallCohort(4)).toBe(true);
    expect(isSmallCohort(5)).toBe(false);
    expect(isSmallCohort(120)).toBe(false);
  });
});
