import { describe, expect, it } from "vitest";
import type { CompareSubject, DrillTrendPoint } from "../api/types";
import { buildCompareMixOption, buildCompareTrendOption } from "./compareOption";

function point(start: string, own: number, composite: number, search: number, share: number, other: number): DrillTrendPoint {
  return { periodStart: start, ownEntry: own, compositeEntry: composite, search, share, other, impressions: 0, clicks: 0, ctr: null, newFollowers: 0, lostFollowers: 0 };
}

function subject(name: string, pv: number, mix: [number, number, number, number, number], trend: DrillTrendPoint[]): CompareSubject {
  const empty = {
    content: { pv: 0, uv: 0, interactions: 0, interactionRate: null, top1Share: null, top3Share: null,
      sourceMix: { ownEntry: 0, compositeEntry: 0, search: 0, share: 0, other: 0 }, ownEntryShare: null, compositeDependency: null },
    supply: { contents: 0, authors: 0 }, exposure: { impressions: 0, clicks: 0, ctr: null, avgDwellSeconds: null, completionRate: null }, followers: null,
  };
  return {
    id: 1, name, extra: null, previous: empty, ownEntry: null, trend,
    current: { ...empty, content: { ...empty.content, pv, sourceMix: { ownEntry: mix[0], compositeEntry: mix[1], search: mix[2], share: mix[3], other: mix[4] } } },
  };
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const series = (option: any): any[] => option.series;

describe("buildCompareTrendOption", () => {
  it("draws one line per subject with the total PV of each period", () => {
    const subjects = [
      subject("甲", 10, [1, 2, 3, 4, 5], [point("2025-06-02", 1, 2, 3, 4, 5), point("2025-06-09", 0, 0, 0, 0, 7)]),
      subject("乙", 5, [0, 0, 0, 0, 5], [point("2025-06-02", 0, 0, 0, 0, 1), point("2025-06-09", 2, 0, 0, 0, 0)]),
    ];

    const option = buildCompareTrendOption(subjects, "WEEK");

    expect(series(option).map((s) => s.name)).toEqual(["甲", "乙"]);
    expect(series(option)[0].data).toEqual([15, 7]);
    expect(series(option)[1].data).toEqual([1, 2]);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect((option as any).xAxis.data).toEqual(["2025-06-02 周", "2025-06-09 周"]);
  });

  it("copes with no subjects", () => {
    expect(series(buildCompareTrendOption([], "DAY"))).toEqual([]);
  });
});

describe("buildCompareMixOption", () => {
  it("stacks the five source shares per subject and gives zero shares to a subject without PV", () => {
    const subjects = [subject("甲", 100, [10, 20, 30, 15, 25], []), subject("乙", 0, [0, 0, 0, 0, 0], [])];

    const option = buildCompareMixOption(subjects);

    expect(series(option).map((s) => s.name)).toEqual(["自主入口", "综合入口", "搜索", "分享", "其他"]);
    expect(series(option)[0].data).toEqual([0.1, 0]);
    expect(series(option)[2].data).toEqual([0.3, 0]);
    expect(series(option).every((s) => s.stack === "mix")).toBe(true);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect((option as any).yAxis.data).toEqual(["甲", "乙"]);
  });
});
