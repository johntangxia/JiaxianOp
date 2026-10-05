import { describe, expect, it } from "vitest";
import type { GrowthTaskDetail } from "../api/types";
import { buildTaskEffectOption } from "./taskEffectOption";

function detail(metric: GrowthTaskDetail["view"]["task"]["metric"], baseline: number | null, values: Array<number | null>, startIndex: number): GrowthTaskDetail {
  return {
    asOf: "2025-09-04",
    view: {
      currentValue: 1, progress: 0.5,
      task: { id: 1, title: "t", taskType: "OTHER", entityType: "OVERALL", entityId: null, entityName: "全社区", metric, baselineValue: baseline,
        targetValue: 0.5, ownerName: null, startDate: "2025-08-20", dueDate: null, status: "DOING", note: null, alertId: null, createdAt: "", updatedAt: "" },
    },
    series: values.map((value, i) => ({ windowEnd: `2025-08-${String(10 + i * 7).padStart(2, "0")}`, value, containsStart: i === startIndex })),
  };
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const option = (d: GrowthTaskDetail): any => buildTaskEffectOption(d);

describe("buildTaskEffectOption", () => {
  it("plots the window values, keeps gaps as null and labels the x axis with month-day", () => {
    const o = option(detail("ACTIVE_RATE", 0.2, [0.2, null, 0.3], 1));

    expect(o.series[0].data).toEqual([0.2, null, 0.3]);
    expect(o.series[0].connectNulls).toBe(false);
    expect(o.xAxis.data).toEqual(["08-10", "08-17", "08-24"]);
  });

  it("marks the baseline, the target and the start window", () => {
    const marks = option(detail("ACTIVE_RATE", 0.2, [0.2, 0.25, 0.3], 1)).series[0].markLine.data;

    expect(marks.map((m: { name: string }) => m.name)).toEqual(["基线", "目标", "开始"]);
    expect(marks[0].yAxis).toBe(0.2);
    expect(marks[1].yAxis).toBe(0.5);
    expect(marks[2].xAxis).toBe(1);
  });

  it("omits the baseline when there was none and the start line when the window is outside the series", () => {
    const marks = option(detail("ACTIVE_RATE", null, [0.2, 0.25], -1)).series[0].markLine.data;

    expect(marks.map((m: { name: string }) => m.name)).toEqual(["目标"]);
  });

  it("formats ratio metrics as percentages and counts as plain numbers", () => {
    const percent = option(detail("PENETRATION", 0.2, [0.2], 0));
    const count = option(detail("PV", 100, [100], 0));

    expect(percent.yAxis.axisLabel.formatter(0.35)).toBe("35%");
    expect(count.yAxis.axisLabel.formatter(120)).toBe("120");
    expect(percent.tooltip.valueFormatter(0.123)).toBe("12.3%");
    expect(count.tooltip.valueFormatter(1234)).toBe("1,234");
    expect(count.tooltip.valueFormatter(null)).toBe("—");
  });
});
