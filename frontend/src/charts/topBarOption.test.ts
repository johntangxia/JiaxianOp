import { describe, expect, it } from "vitest";
import type { PvTopEntry } from "../api/types";
import { buildTopBarOption } from "./topBarOption";

const entries: PvTopEntry[] = [
  { id: 7, name: "半导体", pv: 3456 },
  { id: 3, name: "AI学习", pv: 1200 },
];

describe("buildTopBarOption", () => {
  it("lists names top to bottom in the given order with their PV", () => {
    const option = buildTopBarOption(entries);
    const yAxis = option.yAxis as { data: string[]; inverse: boolean };
    const series = option.series as unknown as Array<{ type: string; data: number[] }>;

    expect(yAxis.data).toEqual(["半导体", "AI学习"]);
    expect(yAxis.inverse).toBe(true);
    expect(series[0].type).toBe("bar");
    expect(series[0].data).toEqual([3456, 1200]);
  });

  it("copes with an empty list", () => {
    const option = buildTopBarOption([]);

    expect((option.yAxis as { data: string[] }).data).toEqual([]);
  });
});
