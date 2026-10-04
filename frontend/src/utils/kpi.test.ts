import { describe, expect, it } from "vitest";
import type { PvTrend } from "../api/types";
import { deriveKpis } from "./kpi";

const trend: PvTrend = {
  granularity: "MONTH",
  from: "2026-01-01",
  to: "2026-04-30",
  points: [
    { periodStart: "2026-01-01", pv: 100, momRate: null, yoyRate: 0.5, complete: true },
    { periodStart: "2026-02-01", pv: 120, momRate: 0.2, yoyRate: null, complete: true },
    { periodStart: "2026-03-01", pv: 60, momRate: -0.5, yoyRate: null, complete: false },
  ],
  forecast: [
    { periodStart: "2026-03-01", value: 130, lower: 110, upper: 150 },
    { periodStart: "2026-04-01", value: 140, lower: 120, upper: 160 },
  ],
};

describe("deriveKpis", () => {
  it("sums the points, picks the last complete period and the first forecast", () => {
    const kpis = deriveKpis(trend);

    expect(kpis.totalPv).toBe(280);
    expect(kpis.latest?.periodStart).toBe("2026-02-01");
    expect(kpis.latest?.momRate).toBe(0.2);
    expect(kpis.nextForecast?.periodStart).toBe("2026-03-01");
  });

  it("has no latest period when nothing is complete and no forecast when there is none", () => {
    const kpis = deriveKpis({
      ...trend,
      points: [{ periodStart: "2026-03-01", pv: 60, momRate: null, yoyRate: null, complete: false }],
      forecast: [],
    });

    expect(kpis.latest).toBeNull();
    expect(kpis.nextForecast).toBeNull();
    expect(kpis.totalPv).toBe(60);
  });
});
