import { describe, expect, it } from "vitest";
import type { CompareSubject, Comparison, DrillTrendPoint, SubjectMetrics } from "../api/types";
import { bestIndexes, compareLink, compareRows, defaultGranularity, rangeFromQuery } from "./drill";

function metrics(pv: number, extra: Partial<SubjectMetrics["content"]> = {}, followers: SubjectMetrics["followers"] = null): SubjectMetrics {
  return {
    content: {
      pv, uv: pv / 2, interactions: 0, interactionRate: 0.1, top1Share: 0.3, top3Share: 0.6,
      sourceMix: { ownEntry: 0, compositeEntry: 0, search: 0, share: 0, other: pv }, ownEntryShare: 0.05, compositeDependency: 0.2, ...extra,
    },
    supply: { contents: 3, authors: 1 },
    exposure: { impressions: 100, clicks: 10, ctr: 0.1, avgDwellSeconds: 60, completionRate: 0.5 },
    followers,
  };
}

function subject(id: number, current: SubjectMetrics, previous: SubjectMetrics = metrics(0), ownEntry: CompareSubject["ownEntry"] = null): CompareSubject {
  return { id, name: `对象${id}`, extra: null, current, previous, ownEntry, trend: [] as DrillTrendPoint[] };
}

function comparison(type: Comparison["type"], subjects: CompareSubject[]): Comparison {
  return { type, from: "2025-06-01", to: "2025-06-30", previousFrom: "2025-05-02", previousTo: "2025-05-31", granularity: "DAY", subjects };
}

describe("bestIndexes", () => {
  it("returns the index of the highest value, including ties", () => {
    expect(bestIndexes([3, 9, 9, 1], true)).toEqual([1, 2]);
  });

  it("returns the lowest when lower is better", () => {
    expect(bestIndexes([0.4, 0.1, 0.3], false)).toEqual([1]);
  });

  it("ignores missing values and handles all-missing", () => {
    expect(bestIndexes([null, 5, undefined, 2], true)).toEqual([1]);
    expect(bestIndexes([null, undefined], true)).toEqual([]);
  });
});

describe("compareRows", () => {
  it("marks the best subject per metric using each metric's direction", () => {
    const rows = compareRows(comparison("ACCOUNT", [
      subject(1, metrics(100, { top1Share: 0.5 })), subject(2, metrics(200, { top1Share: 0.2 })),
    ]));

    const pv = rows.find((r) => r.key === "pv")!;
    expect(pv.cells.map((c) => c.best)).toEqual([false, true]);
    expect(pv.cells[1].text).toBe("200");
    const concentration = rows.find((r) => r.key === "top1Share")!;
    expect(concentration.cells.map((c) => c.best)).toEqual([false, true]);   // 越小越好
  });

  it("does not crown anyone when all values are equal", () => {
    const rows = compareRows(comparison("ACCOUNT", [subject(1, metrics(100)), subject(2, metrics(100))]));

    expect(rows.find((r) => r.key === "pv")!.cells.every((c) => !c.best)).toBe(true);
  });

  it("shows follower rows only for accounts and own-entry rows only for channels", () => {
    const account = compareRows(comparison("ACCOUNT", [
      subject(1, metrics(10, {}, { followers: 5, newFollowers: 2, lostFollowers: 1 })),
      subject(2, metrics(10, {}, { followers: 9, newFollowers: 1, lostFollowers: 0 })),
    ]));
    expect(account.map((r) => r.key)).toContain("followers");
    expect(account.map((r) => r.key)).not.toContain("ownCtr");
    expect(account.find((r) => r.key === "lostFollowers")!.cells.map((c) => c.best)).toEqual([false, true]);

    const channel = compareRows(comparison("CHANNEL", [
      subject(1, metrics(10), metrics(0), { impressions: 100, clicks: 12, ctr: 0.12, reachUv: 5, clickUv: 3, avgDwellSeconds: null }),
      subject(2, metrics(10), metrics(0), { impressions: 100, clicks: 4, ctr: 0.04, reachUv: 5, clickUv: 3, avgDwellSeconds: null }),
    ]));
    expect(channel.map((r) => r.key)).toContain("ownCtr");
    expect(channel.map((r) => r.key)).not.toContain("followers");
    expect(channel.find((r) => r.key === "ownCtr")!.cells.map((c) => c.text)).toEqual(["12.0%", "4.0%"]);

    const blogger = compareRows(comparison("BLOGGER", [subject(1, metrics(10)), subject(2, metrics(20))]));
    expect(blogger.map((r) => r.key)).not.toContain("followers");
    expect(blogger.map((r) => r.key)).not.toContain("ownCtr");
  });

  it("carries the previous value for absolute metrics so the table can show the change", () => {
    const rows = compareRows(comparison("BLOGGER", [subject(1, metrics(100), metrics(50)), subject(2, metrics(10), metrics(20))]));
    const pv = rows.find((r) => r.key === "pv")!;

    expect(pv.cells.map((c) => [c.current, c.previous])).toEqual([[100, 50], [10, 20]]);
    expect(rows.find((r) => r.key === "interactionRate")!.cells[0].previous).toBeNull();
  });
});

describe("routing helpers", () => {
  it("builds the compare link with ids and range", () => {
    expect(compareLink("ACCOUNT", [3, 5], { from: "2025-06-01", to: "2025-06-30" })).toEqual({
      name: "compare", query: { type: "ACCOUNT", ids: "3,5", from: "2025-06-01", to: "2025-06-30" },
    });
  });

  it("reads a valid range from the query and rejects anything else", () => {
    expect(rangeFromQuery({ from: "2025-06-01", to: "2025-06-30" })).toEqual({ from: "2025-06-01", to: "2025-06-30" });
    expect(rangeFromQuery({ from: "2025-6-1", to: "2025-06-30" })).toBeNull();
    expect(rangeFromQuery({})).toBeNull();
  });

  it("looks at weeks for ranges longer than 45 days", () => {
    expect(defaultGranularity({ from: "2025-06-01", to: "2025-06-30" })).toBe("DAY");
    expect(defaultGranularity({ from: "2025-06-01", to: "2025-07-15" })).toBe("DAY");   // 45 天
    expect(defaultGranularity({ from: "2025-06-01", to: "2025-07-16" })).toBe("WEEK");  // 46 天
  });
});
