import { describe, expect, it } from "vitest";
import type { CreatorConcentrationShare, CreatorLadderStage, CreatorPoint, UserLifecycleStageRow, UserShare, UserValueSegmentRow, UserWeekActivity } from "../api/types";
import { buildConcentrationOption, buildCreatorTrendOption, buildLadderOption, buildLifecycleOption, buildSourcePieOption, buildTimelineOption, buildValuePieOption } from "./peopleOption";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Loose = any;
const series = (option: unknown): Loose[] => (option as Loose).series;

describe("buildCreatorTrendOption", () => {
  const points: CreatorPoint[] = [
    { periodStart: "2025-05-01", activeCreators: 4, newCreators: 1, posts: 8, postsPerCreator: 2 },
    { periodStart: "2025-06-01", activeCreators: 0, newCreators: 0, posts: 0, postsPerCreator: null },
  ];

  it("draws creators as bars and posts per creator as a gap-preserving line on the right axis", () => {
    const s = series(buildCreatorTrendOption(points, "MONTH"));

    expect(s.map((x) => x.name)).toEqual(["活跃创作者", "新增创作者", "人均发帖"]);
    expect(s[0].data).toEqual([4, 0]);
    expect(s[2]).toMatchObject({ type: "line", yAxisIndex: 1, connectNulls: false });
    expect(s[2].data).toEqual([2, null]);
    expect((buildCreatorTrendOption(points, "MONTH") as Loose).xAxis.data).toEqual(["2025-05", "2025-06"]);
  });
});

describe("buildLadderOption", () => {
  const stages: CreatorLadderStage[] = [
    { stage: "INACTIVE", label: "未激活", count: 5, share: 0.5 },
    { stage: "KOL", label: "KOL", count: 0, share: 0 },
  ];

  it("lists stages top to bottom, colours each and labels count with share", () => {
    const option = buildLadderOption(stages) as Loose;

    expect(option.yAxis.data).toEqual(["未激活", "KOL"]);
    expect(option.yAxis.inverse).toBe(true);
    expect(option.series[0].data.map((d: Loose) => d.value)).toEqual([5, 0]);
    expect(option.series[0].data[0].itemStyle.color).not.toBe(option.series[0].data[1].itemStyle.color);
    expect(option.series[0].label.formatter({ dataIndex: 0 })).toBe("5（50%）");
  });
});

describe("buildConcentrationOption", () => {
  it("puts PV share and post share side by side, keeping missing values empty", () => {
    const shares: CreatorConcentrationShare[] = [
      { label: "前 1 名", creators: 1, pvShare: 0.4, postShare: 0.1 },
      { label: "前 5 名", creators: 5, pvShare: null, postShare: null },
    ];

    const option = buildConcentrationOption(shares) as Loose;

    expect(option.xAxis.data).toEqual(["前 1 名（1 人）", "前 5 名（5 人）"]);
    expect(option.series.map((s: Loose) => s.name)).toEqual(["PV 占比", "发帖占比"]);
    expect(option.series[0].data).toEqual([0.4, null]);
    expect(option.series[0].label.formatter({ value: 0.4 })).toBe("40%");
    expect(option.series[0].label.formatter({ value: null })).toBe("—");
    expect(option.yAxis.max).toBe(1);
  });
});

describe("buildLifecycleOption", () => {
  const stages: UserLifecycleStageRow[] = [
    { stage: "NEW", label: "新成员", count: 3, share: 0.3, previousCount: 0 },
    { stage: "ACTIVE", label: "活跃", count: 5, share: 0.5, previousCount: 4 },
  ];

  it("compares the current and previous counts per stage with a fixed colour per stage", () => {
    const s = series(buildLifecycleOption(stages));

    expect(s.map((x) => x.name)).toEqual(["上一时点", "当前"]);
    expect(s[0].data.map((d: Loose) => d.value)).toEqual([0, 4]);
    expect(s[1].data.map((d: Loose) => d.value)).toEqual([3, 5]);
    expect(s[0].data[0].itemStyle.color).toBe(s[1].data[0].itemStyle.color);
    expect(s[0].data[0].itemStyle.opacity).toBeLessThan(1);
  });
});

describe("buildValuePieOption", () => {
  it("skips empty segments and colours each one", () => {
    const segments: UserValueSegmentRow[] = [
      { segment: "CORE", label: "核心", count: 2, share: 0.2, avgEngagement: 10, avgActiveDays: 5 },
      { segment: "TO_WAKE", label: "待唤醒", count: 0, share: 0, avgEngagement: null, avgActiveDays: null },
      { segment: "SLEEPING", label: "沉睡", count: 8, share: 0.8, avgEngagement: 0, avgActiveDays: 0 },
    ];

    const data = series(buildValuePieOption(segments))[0].data;

    expect(data.map((d: Loose) => d.name)).toEqual(["核心", "沉睡"]);
    expect(data[0].itemStyle.color).not.toBe(data[1].itemStyle.color);
  });
});

describe("buildTimelineOption", () => {
  const weeks: UserWeekActivity[] = [
    { weekStart: "2025-06-02", logins: 3, views: 10, interactions: 2, posts: 1 },
    { weekStart: "2025-06-09", logins: 0, views: 0, interactions: 0, posts: 0 },
  ];

  it("stacks logins, views, interactions and posts per week", () => {
    const option = buildTimelineOption(weeks) as Loose;

    expect(option.xAxis.data).toEqual(["06-02", "06-09"]);
    expect(option.series.map((s: Loose) => s.name)).toEqual(["登录", "浏览", "互动", "发帖"]);
    expect(option.series.every((s: Loose) => s.stack === "activity")).toBe(true);
    expect(option.series[1].data).toEqual([10, 0]);
  });
});

describe("buildSourcePieOption", () => {
  it("translates the source names and skips sources without views", () => {
    const sources: UserShare[] = [
      { label: "ENTRY", count: 3, share: 0.3 },
      { label: "SEARCH", count: 0, share: 0 },
      { label: "SHARE", count: 7, share: 0.7 },
    ];

    const data = series(buildSourcePieOption(sources))[0].data;

    expect(data.map((d: Loose) => d.name)).toEqual(["频道入口", "分享"]);
    expect(data.map((d: Loose) => d.value)).toEqual([3, 7]);
  });
});
