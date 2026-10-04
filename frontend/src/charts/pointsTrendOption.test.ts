import { describe, expect, it } from "vitest";
import type { PointsTrendSeries } from "../api/types";
import { buildPointsTrendOption } from "./pointsTrendOption";

const series: PointsTrendSeries = {
  granularity: "MONTH",
  from: "2026-01-01",
  to: "2026-02-28",
  points: [
    { periodStart: "2026-01-01", activePoints: 500, passivePoints: 40, total: 540, complete: true },
    { periodStart: "2026-02-01", activePoints: 100, passivePoints: 0, total: 100, complete: true },
  ],
};

type Bar = { name: string; stack: string; data: number[] };

describe("buildPointsTrendOption", () => {
  it("stacks active and passive points per period", () => {
    const option = buildPointsTrendOption(series);
    const bars = option.series as unknown as Bar[];

    expect((option.xAxis as { data: string[] }).data).toEqual(["2026-01", "2026-02"]);
    expect(bars.map((b) => b.name)).toEqual(["主动积分", "被动积分"]);
    expect(bars[0].data).toEqual([500, 100]);
    expect(bars[1].data).toEqual([40, 0]);
    expect(bars[0].stack).toBe(bars[1].stack);
  });
});
