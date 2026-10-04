import { describe, expect, it } from "vitest";
import type { ActivitySeries } from "../api/types";
import { buildActivityOption } from "./activityOption";

const series: ActivitySeries = {
  granularity: "MONTH",
  from: "2026-01-01",
  to: "2026-02-28",
  points: [
    { periodStart: "2026-01-01", posts: 2, comments: 1, logins: 20, complete: true },
    { periodStart: "2026-02-01", posts: 0, comments: 3, logins: 18, complete: true },
  ],
};

type Bar = { name: string; stack: string; data: number[] };

describe("buildActivityOption", () => {
  it("stacks posts, comments and logins per period", () => {
    const option = buildActivityOption(series);
    const bars = option.series as unknown as Bar[];

    expect((option.xAxis as { data: string[] }).data).toEqual(["2026-01", "2026-02"]);
    expect(bars.map((b) => b.name)).toEqual(["发帖", "评论", "登录"]);
    expect(bars[0].data).toEqual([2, 0]);
    expect(bars[1].data).toEqual([1, 3]);
    expect(bars[2].data).toEqual([20, 18]);
    expect(new Set(bars.map((b) => b.stack)).size).toBe(1);
  });

  it("labels weekly periods with the week suffix", () => {
    const option = buildActivityOption({ ...series, granularity: "WEEK" });

    expect((option.xAxis as { data: string[] }).data).toEqual(["2026-01-01 周", "2026-02-01 周"]);
  });
});
