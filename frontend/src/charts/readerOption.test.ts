import { describe, expect, it } from "vitest";
import type { ReaderActivityPoint, ReaderFunnelStep, ReaderSegmentCount } from "../api/types";
import { buildFunnelOption, buildReaderActivityOption, buildSegmentPieOption } from "./readerOption";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const series = (option: any): any[] => option.series;

describe("buildReaderActivityOption", () => {
  const points: ReaderActivityPoint[] = [
    { periodStart: "2025-06-02", activeUsers: 10, newMembers: 2, members: 20, activeRate: 0.5 },
    { periodStart: "2025-06-09", activeUsers: 0, newMembers: 0, members: 0, activeRate: null },
  ];

  it("draws active users and new members as bars and the active rate as a gap-preserving line on the right axis", () => {
    const s = series(buildReaderActivityOption(points, "WEEK"));

    expect(s.map((x) => x.name)).toEqual(["活跃人数", "新增成员", "活跃率"]);
    expect(s[0].data).toEqual([10, 0]);
    expect(s[1].data).toEqual([2, 0]);
    expect(s[2]).toMatchObject({ type: "line", yAxisIndex: 1, connectNulls: false });
    expect(s[2].data).toEqual([0.5, null]);
  });

  it("labels the x axis by granularity", () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect((buildReaderActivityOption(points, "MONTH") as any).xAxis.data).toEqual(["2025-06", "2025-06"]);
  });
});

describe("buildFunnelOption", () => {
  const steps: ReaderFunnelStep[] = [
    { key: "JOINED", label: "加入社区", count: 10, rateOfJoined: 1 },
    { key: "VIEWED", label: "7 天内首次浏览", count: 7, rateOfJoined: 0.7 },
  ];

  it("lists the steps top to bottom with counts and shows count plus rate as the label", () => {
    const option = buildFunnelOption(steps) as any; // eslint-disable-line @typescript-eslint/no-explicit-any

    expect(option.yAxis.data).toEqual(["加入社区", "7 天内首次浏览"]);
    expect(option.yAxis.inverse).toBe(true);
    expect(option.series[0].data).toEqual([10, 7]);
    expect(option.series[0].label.formatter({ dataIndex: 1 })).toBe("7（70%）");
  });

  it("shows a dash instead of a rate when nobody joined", () => {
    const option = buildFunnelOption([{ key: "JOINED", label: "加入社区", count: 0, rateOfJoined: null }]) as any; // eslint-disable-line @typescript-eslint/no-explicit-any

    expect(option.series[0].label.formatter({ dataIndex: 0 })).toBe("0（—）");
  });
});

describe("buildSegmentPieOption", () => {
  it("skips empty roles and colours each role", () => {
    const segments: ReaderSegmentCount[] = [
      { role: "CREATOR", label: "创作者", count: 3, share: 0.3 },
      { role: "LURKER", label: "沉默读者", count: 7, share: 0.7 },
      { role: "INACTIVE", label: "不活跃", count: 0, share: 0 },
    ];

    const data = series(buildSegmentPieOption(segments))[0].data;

    expect(data.map((d: { name: string }) => d.name)).toEqual(["创作者", "沉默读者"]);
    expect(data[1].itemStyle.color).toBe("#f08c2c");
  });
});
