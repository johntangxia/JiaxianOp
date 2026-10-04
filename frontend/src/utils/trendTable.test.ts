import { describe, expect, it } from "vitest";
import type { PvTrend } from "../api/types";
import { buildTrendRows } from "./trendTable";

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

describe("buildTrendRows", () => {
  it("lists forecast and actual rows newest first, forecast before actual for the same period", () => {
    const rows = buildTrendRows(trend);

    expect(rows.map((r) => r.key)).toEqual([
      "forecast-2026-04-01",
      "forecast-2026-03-01",
      "actual-2026-03-01",
      "actual-2026-02-01",
      "actual-2026-01-01",
    ]);
  });

  it("labels each row and fills the range only for forecasts", () => {
    const rows = buildTrendRows(trend);

    expect(rows[0]).toMatchObject({ label: "2026-04", pv: 140, kind: "forecast", range: "120 ~ 160", momRate: null });
    expect(rows[2]).toMatchObject({ pv: 60, kind: "incomplete", momRate: -0.5, range: null });
    expect(rows[3]).toMatchObject({ pv: 120, kind: "complete", momRate: 0.2, yoyRate: null });
  });

  it("handles an empty forecast", () => {
    expect(buildTrendRows({ ...trend, forecast: [] }).every((r) => r.kind !== "forecast")).toBe(true);
  });
});
