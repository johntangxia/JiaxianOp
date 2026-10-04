import { describe, expect, it } from "vitest";
import type { LeaderboardEntry } from "../api/types";
import { buildLeaderboardOption } from "./leaderboardOption";

const entries: LeaderboardEntry[] = [
  { rank: 1, employeeId: 1, name: "员工-1", points: 200, activePoints: 100, passivePoints: 100 },
  { rank: 2, employeeId: 2, name: "员工-2", points: 150, activePoints: 150, passivePoints: 0 },
];

type Series = { name: string; stack: string; data: number[] };

describe("buildLeaderboardOption", () => {
  it("stacks active and passive points per person, top rank first", () => {
    const option = buildLeaderboardOption(entries);
    const series = option.series as unknown as Series[];

    expect((option.yAxis as { data: string[]; inverse: boolean }).data).toEqual(["员工-1", "员工-2"]);
    expect((option.yAxis as { inverse: boolean }).inverse).toBe(true);
    expect(series.map((s) => s.name)).toEqual(["主动积分", "被动积分"]);
    expect(series[0].data).toEqual([100, 150]);
    expect(series[1].data).toEqual([100, 0]);
    expect(series[0].stack).toBe(series[1].stack);
  });
});
