import { describe, expect, it } from "vitest";
import type { HistoryPoint } from "../api/types";
import { buildRfmHistoryOption, rfmHistoryTooltip } from "./rfmHistoryOption";

const history: HistoryPoint[] = [
  { asOf: "2026-02-22", rank: 2, segment: "STAR_KOL", segmentName: "明星KOL", churnAlert: false, rScore: 5, fScore: 5, mScore: 4 },
  { asOf: "2026-03-01", rank: 7, segment: "CHURN_RISK", segmentName: "流失预警", churnAlert: true, rScore: 2, fScore: 5, mScore: 4 },
];

type Series = { name: string; data: number[] };

describe("buildRfmHistoryOption", () => {
  it("draws one line per score over the weekly runs", () => {
    const option = buildRfmHistoryOption(history);
    const series = option.series as unknown as Series[];

    expect((option.xAxis as { data: string[] }).data).toEqual(["2026-02-22", "2026-03-01"]);
    expect(series.map((s) => s.name)).toEqual(["新近度 R", "频次 F", "产出 M"]);
    expect(series[0].data).toEqual([5, 2]);
    expect(series[1].data).toEqual([5, 5]);
    expect(series[2].data).toEqual([4, 4]);
  });

  it("fixes the y axis to the 0-5 score range", () => {
    const yAxis = buildRfmHistoryOption(history).yAxis as { min: number; max: number; interval: number };

    expect(yAxis).toMatchObject({ min: 0, max: 5, interval: 1 });
  });

  it("copes with an empty history", () => {
    const option = buildRfmHistoryOption([]);

    expect((option.xAxis as { data: string[] }).data).toEqual([]);
  });
});

describe("rfmHistoryTooltip", () => {
  it("shows the segment, rank and scores for the week", () => {
    const text = rfmHistoryTooltip(history, 1);

    expect(text).toContain("2026-03-01");
    expect(text).toContain("流失预警（预警）");
    expect(text).toContain("名次 7");
    expect(text).toContain("R 2");
    expect(text).toContain("F 5");
    expect(text).toContain("M 4");
  });

  it("omits the alert note when there is no alert", () => {
    const text = rfmHistoryTooltip(history, 0);

    expect(text).toContain("明星KOL");
    expect(text).not.toContain("预警");
  });
});
