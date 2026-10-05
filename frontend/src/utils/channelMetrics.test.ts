import { describe, expect, it } from "vitest";
import type { ChannelPeriod, ChannelRow, Diagnosis } from "../api/types";
import {
  changeRate,
  compareNullable,
  defaultCockpitRange,
  formatPercent,
  formatSeconds,
  severityTagType,
  sumDistribution,
  sumSourceMix,
  worstSeverity,
} from "./channelMetrics";

function period(impressions: number, clicks: number, ownEntry = 0, compositeEntry = 0): ChannelPeriod {
  return {
    distribution: { impressions, clicks, ctr: impressions ? clicks / impressions : null, reachUv: 0, clickUv: 0, avgDwellSeconds: null },
    content: {
      pv: ownEntry + compositeEntry + 10, uv: 0, interactions: 0, interactionRate: null, top1Share: null, top3Share: null,
      sourceMix: { ownEntry, compositeEntry, search: 4, share: 5, other: 1 }, ownEntryShare: null, compositeDependency: null,
    },
    supply: null,
    coverage: null,
  };
}

function row(current: ChannelPeriod, previous: ChannelPeriod = current): ChannelRow {
  return { id: 1, name: "x", category: "领域频道", current, previous, diagnoses: [] };
}

describe("changeRate", () => {
  it("is the relative change versus the previous period", () => {
    expect(changeRate(150, 100)).toBe(0.5);
    expect(changeRate(50, 100)).toBe(-0.5);
  });

  it("has no meaning without a positive previous value", () => {
    expect(changeRate(10, 0)).toBeNull();
    expect(changeRate(10, null)).toBeNull();
    expect(changeRate(null, 10)).toBeNull();
  });
});

describe("formatters", () => {
  it("formats percentages and missing values", () => {
    expect(formatPercent(0.1234)).toBe("12.3%");
    expect(formatPercent(0.02, 0)).toBe("2%");
    expect(formatPercent(null)).toBe("—");
  });

  it("formats seconds as minutes when needed", () => {
    expect(formatSeconds(45)).toBe("45 秒");
    expect(formatSeconds(85)).toBe("1 分 25 秒");
    expect(formatSeconds(null)).toBe("—");
  });
});

describe("sumDistribution", () => {
  it("adds impressions and clicks and derives the overall CTR instead of averaging CTRs", () => {
    const rows = [row(period(100, 10)), row(period(900, 30))];

    expect(sumDistribution(rows, "current")).toEqual({ impressions: 1000, clicks: 40, ctr: 0.04 });
  });

  it("has no CTR without impressions and can read the previous period", () => {
    expect(sumDistribution([row(period(0, 0))], "current").ctr).toBeNull();
    expect(sumDistribution([row(period(10, 1), period(200, 50))], "previous").clicks).toBe(50);
  });
});

describe("sumSourceMix", () => {
  it("adds each source across channels and skips channels without content", () => {
    const composite: ChannelRow = { ...row(period(1, 1)), current: { ...period(1, 1), content: null } };
    const total = sumSourceMix([row(period(1, 1, 3, 7)), row(period(1, 1, 2, 3)), composite], "current");

    expect(total).toMatchObject({ ownEntry: 5, compositeEntry: 10, search: 8, share: 10, other: 2, total: 35 });
  });
});

describe("severity helpers", () => {
  const d = (severity: Diagnosis["severity"]): Diagnosis => ({ code: "X", severity, message: "m" });

  it("maps severities to element-plus tag types", () => {
    expect(severityTagType("CRITICAL")).toBe("danger");
    expect(severityTagType("WARN")).toBe("warning");
    expect(severityTagType("INFO")).toBe("info");
  });

  it("finds the worst severity", () => {
    expect(worstSeverity([d("INFO"), d("CRITICAL"), d("WARN")])).toBe("CRITICAL");
    expect(worstSeverity([d("INFO"), d("WARN")])).toBe("WARN");
    expect(worstSeverity([])).toBeNull();
  });
});

describe("compareNullable", () => {
  it("sorts missing values first", () => {
    expect([3, null, 1, null, 2].sort(compareNullable)).toEqual([null, null, 1, 2, 3]);
    expect(compareNullable(undefined, 1)).toBe(-1);   // undefined 在数组 sort 里总排最后，但直接比较时和 null 一样最小
  });
});

describe("defaultCockpitRange", () => {
  it("covers 180 days by default, ending at the last PV day", () => {
    expect(defaultCockpitRange({ from: "2024-01-01", to: "2025-09-30" })).toEqual({ from: "2025-04-04", to: "2025-09-30" });
  });

  it("covers the requested number of days ending at the last PV day", () => {
    expect(defaultCockpitRange({ from: "2025-01-01", to: "2025-09-30" }, 30)).toEqual({ from: "2025-09-01", to: "2025-09-30" });
  });

  it("does not start before the first PV day", () => {
    expect(defaultCockpitRange({ from: "2025-09-20", to: "2025-09-30" }, 30)).toEqual({ from: "2025-09-20", to: "2025-09-30" });
  });

  it("crosses month boundaries", () => {
    expect(defaultCockpitRange({ from: "2025-01-01", to: "2025-03-10" }, 30)).toEqual({ from: "2025-02-09", to: "2025-03-10" });
  });

  it("is null without data", () => {
    expect(defaultCockpitRange(null)).toBeNull();
    expect(defaultCockpitRange({ from: null, to: null })).toBeNull();
  });
});
