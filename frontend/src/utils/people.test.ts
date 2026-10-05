import { describe, expect, it } from "vitest";
import type { UserTransitionRow } from "../api/types";
import { creatorStageColor, formatDays, giniLabel, lifecycleColor, lifecycleTagType, matrixCell, shortTime, sourceLabel, stayed, userLink, valueColor, valueTagType, weekLabel } from "./people";

describe("userLink", () => {
  it("links to the 360 page and carries asOf only when given", () => {
    expect(userLink(7)).toEqual({ name: "user-detail", params: { id: 7 } });
    expect(userLink(7, "2025-06-30")).toEqual({ name: "user-detail", params: { id: 7 }, query: { asOf: "2025-06-30" } });
  });
});

describe("colours and tag types", () => {
  it("gives every lifecycle stage, value segment and creator stage a colour", () => {
    expect(lifecycleColor("CHURNED")).toMatch(/^#/);
    expect(new Set(["NEW", "ACTIVE", "RETURNING", "LAPSING", "CHURNED", "DORMANT"].map((s) => lifecycleColor(s as never))).size).toBe(6);
    expect(new Set(["CORE", "ACTIVE", "TO_WAKE", "LOW_ACTIVE", "SLEEPING"].map((s) => valueColor(s as never))).size).toBe(5);
    expect(new Set(["INACTIVE", "READER", "INTERACTOR", "CREATOR", "ACTIVE_CREATOR", "KOL"].map((s) => creatorStageColor(s as never))).size).toBe(6);
  });

  it("flags the stages that need attention", () => {
    expect(lifecycleTagType("LAPSING")).toBe("warning");
    expect(lifecycleTagType("CHURNED")).toBe("danger");
    expect(lifecycleTagType("NEW")).toBe("success");
    expect(lifecycleTagType(null)).toBe("info");
    expect(valueTagType("TO_WAKE")).toBe("warning");
    expect(valueTagType("CORE")).toBe("success");
    expect(valueTagType("SLEEPING")).toBe("info");
  });
});

describe("sourceLabel", () => {
  it("translates the known sources and passes unknown ones through", () => {
    expect(sourceLabel("ENTRY")).toBe("频道入口");
    expect(sourceLabel("SHARE")).toBe("分享");
    expect(sourceLabel("WHATEVER")).toBe("WHATEVER");
  });
});

describe("matrixCell", () => {
  it("shows a dot for empty cells and count with share otherwise, darker for bigger shares", () => {
    expect(matrixCell(0, 10).text).toBe("·");
    expect(matrixCell(3, 0).text).toBe("·");
    const small = matrixCell(1, 10);
    const big = matrixCell(9, 10);
    expect(small.text).toBe("1（10%）");
    expect(big.text).toBe("9（90%）");
    expect(small.color).toBe("#1f2937");
    expect(big.color).toBe("#fff");
    expect(Number(big.background.match(/, ([\d.]+)\)/)![1])).toBeGreaterThan(Number(small.background.match(/, ([\d.]+)\)/)![1]));
  });
});

describe("stayed", () => {
  const row = (from: UserTransitionRow["from"]): UserTransitionRow => ({
    from,
    fromLabel: "x",
    total: 3,
    to: [
      { stage: "NEW", label: "新成员", count: 1 },
      { stage: "ACTIVE", label: "活跃", count: 2 },
    ],
  });

  it("is the diagonal cell, and absent for the not-yet-members row", () => {
    expect(stayed(row("ACTIVE"))).toBe(2);
    expect(stayed(row("LAPSING"))).toBe(0);
    expect(stayed(row(null))).toBeNull();
  });
});

describe("formatting helpers", () => {
  it("formats days", () => {
    expect(formatDays(3)).toBe("3 天");
    expect(formatDays(2.5)).toBe("2.5 天");
    expect(formatDays(null)).toBe("—");
  });

  it("describes the gini coefficient", () => {
    expect(giniLabel(0.2)).toBe("0.20（较均匀）");
    expect(giniLabel(0.4)).toBe("0.40（中等集中）");
    expect(giniLabel(0.75)).toBe("0.75（高度集中）");
    expect(giniLabel(null)).toBe("—");
  });

  it("shortens times and week starts", () => {
    expect(shortTime("2025-06-30T09:05:00")).toBe("06-30 09:05");
    expect(weekLabel("2025-06-30")).toBe("06-30");
  });
});
