import { describe, expect, it } from "vitest";
import type { TrendPoint } from "../api/types";
import { buildChannelTrendOption } from "./channelTrendOption";

const trend: TrendPoint[] = [
  { periodStart: "2025-06-02", ownEntry: 1, compositeEntry: 2, search: 3, share: 4, other: 5, impressions: 10, clicks: 3, ctr: 0.3 },
  { periodStart: "2025-06-09", ownEntry: 0, compositeEntry: 0, search: 0, share: 0, other: 0, impressions: 0, clicks: 0, ctr: null },
];

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const series = (option: any): any[] => option.series;

describe("buildChannelTrendOption", () => {
  it("stacks five sources for a domain channel and adds a CTR line on the right axis", () => {
    const s = series(buildChannelTrendOption(trend, "WEEK", false));

    expect(s.map((x) => x.name)).toEqual(["自主入口", "综合入口", "搜索", "分享", "其他", "自主入口点击率"]);
    expect(s.slice(0, 5).every((x) => x.type === "bar" && x.stack === "pv")).toBe(true);
    expect(s[0].data).toEqual([1, 0]);
    expect(s[4].data).toEqual([5, 0]);
    expect(s[5]).toMatchObject({ type: "line", yAxisIndex: 1, connectNulls: false });
  });

  it("shows impressions, clicks and CTR for a composite channel", () => {
    const s = series(buildChannelTrendOption(trend, "WEEK", true));

    expect(s.map((x) => x.name)).toEqual(["曝光", "点击", "点击率"]);
    expect(s[0].data).toEqual([10, 0]);
    expect(s[1].data).toEqual([3, 0]);
    expect(s[0].stack).toBeUndefined();
  });

  it("leaves the CTR gap as null instead of zero", () => {
    const s = series(buildChannelTrendOption(trend, "WEEK", true));

    expect(s[2].data).toEqual([0.3, null]);
  });

  it("labels the x axis by granularity", () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const option = buildChannelTrendOption(trend, "WEEK", false) as any;

    expect(option.xAxis.data).toEqual(["2025-06-02 周", "2025-06-09 周"]);
  });
});
