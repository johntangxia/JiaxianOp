import { describe, expect, it } from "vitest";
import type { PvTrend } from "../api/types";
import { buildTrendOption, trendTooltip } from "./trendOption";

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

type Series = { name: string; data: unknown[] };

function seriesOf(option: ReturnType<typeof buildTrendOption>): Series[] {
  return option.series as unknown as Series[];
}

describe("buildTrendOption", () => {
  it("uses the union of actual and forecast periods as the x axis", () => {
    const option = buildTrendOption(trend);

    expect((option.xAxis as { data: string[] }).data).toEqual(["2026-01", "2026-02", "2026-03", "2026-04"]);
  });

  it("draws incomplete periods as hollow points and leaves forecast-only periods empty", () => {
    const actual = seriesOf(buildTrendOption(trend)).find((s) => s.name === "实际")!;

    expect(actual.data[0]).toBe(100);
    expect(actual.data[1]).toBe(120);
    expect(actual.data[2]).toMatchObject({ value: 60, itemStyle: { color: "#fff" } });
    expect(actual.data[3]).toBeNull();
  });

  it("starts the dashed forecast line at the last complete period", () => {
    const forecast = seriesOf(buildTrendOption(trend)).find((s) => s.name === "预测")!;

    expect(forecast.data).toEqual([null, 120, 130, 140]);
  });

  it("stacks a transparent base and the band width for the confidence interval", () => {
    const series = seriesOf(buildTrendOption(trend));
    const base = series.find((s) => s.name === "区间下限")!;
    const band = series.find((s) => s.name === "预测区间")!;

    expect(base.data).toEqual([null, 120, 110, 120]);
    expect(band.data).toEqual([null, 0, 40, 40]);
  });

  it("only lists the visible series in the legend", () => {
    const legend = buildTrendOption(trend).legend as { data: string[] };

    expect(legend.data).toEqual(["实际", "预测", "预测区间"]);
  });

  it("has no forecast data when there is no forecast", () => {
    const option = buildTrendOption({ ...trend, forecast: [] });
    const series = seriesOf(option);

    expect((option.xAxis as { data: string[] }).data).toEqual(["2026-01", "2026-02", "2026-03"]);
    expect(series.find((s) => s.name === "预测")!.data.every((v) => v === null)).toBe(true);
    expect(series.find((s) => s.name === "预测区间")!.data.every((v) => v === null)).toBe(true);
  });

  it("builds the axis from forecast periods alone when there are no points", () => {
    const option = buildTrendOption({ ...trend, points: [] });

    expect((option.xAxis as { data: string[] }).data).toEqual(["2026-03", "2026-04"]);
  });
});

describe("trendTooltip", () => {
  const starts = ["2026-01-01", "2026-02-01", "2026-03-01", "2026-04-01"];

  it("shows actual value with month-over-month and year-over-year", () => {
    const text = trendTooltip(trend, starts, 0);

    expect(text).toContain("2026-01");
    expect(text).toContain("实际 PV：100");
    expect(text).toContain("环比：—");
    expect(text).toContain("同比：+50.0%");
    expect(text).not.toContain("预测");
  });

  it("marks an in-progress period and shows its forecast too", () => {
    const text = trendTooltip(trend, starts, 2);

    expect(text).toContain("实际 PV：60（进行中）");
    expect(text).toContain("环比：-50.0%");
    expect(text).toContain("预测 PV：130");
    expect(text).toContain("置信区间：110 ~ 150");
  });

  it("shows only the forecast for future periods", () => {
    const text = trendTooltip(trend, starts, 3);

    expect(text).toContain("预测 PV：140");
    expect(text).not.toContain("实际");
  });
});
