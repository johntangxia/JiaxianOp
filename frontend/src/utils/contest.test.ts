import { describe, expect, it } from "vitest";
import type { PeriodOption } from "../api/types";
import { defaultPeriod, formatScore, medalClass, periodLabel, rankMove, scoreChangeText, scoreColor } from "./contest";

const week = (start: string, end: string, complete: boolean): PeriodOption => ({ periodStart: start, periodEnd: end, complete });

describe("rankMove", () => {
  it("shows up, down, flat and missing", () => {
    expect(rankMove(2)).toEqual({ text: "↑2", kind: "up" });
    expect(rankMove(-3)).toEqual({ text: "↓3", kind: "down" });
    expect(rankMove(0)).toEqual({ text: "持平", kind: "flat" });
    expect(rankMove(null)).toEqual({ text: "—", kind: "none" });
  });
});

describe("score formatting", () => {
  it("keeps one decimal", () => {
    expect(formatScore(87.65)).toBe("87.7");
    expect(formatScore(100)).toBe("100.0");
  });

  it("prefixes positive changes with a plus sign and handles missing", () => {
    expect(scoreChangeText(12.34)).toBe("+12.3");
    expect(scoreChangeText(-5)).toBe("-5.0");
    expect(scoreChangeText(0)).toBe("0.0");
    expect(scoreChangeText(null)).toBe("—");
  });
});

describe("periodLabel", () => {
  it("labels weeks with a date range and months in Chinese", () => {
    expect(periodLabel("WEEK", week("2025-06-16", "2025-06-22", true))).toBe("2025-06-16 ~ 06-22");
    expect(periodLabel("MONTH", week("2025-06-01", "2025-06-30", true))).toBe("2025 年 6 月");
  });

  it("flags a period that has not finished", () => {
    expect(periodLabel("MONTH", week("2025-10-01", "2025-10-31", false))).toBe("2025 年 10 月（进行中）");
  });
});

describe("defaultPeriod", () => {
  it("prefers the latest completed period", () => {
    const periods = [week("2025-06-23", "2025-06-29", false), week("2025-06-16", "2025-06-22", true), week("2025-06-09", "2025-06-15", true)];

    expect(defaultPeriod(periods)?.periodStart).toBe("2025-06-16");
  });

  it("falls back to the newest period, or null when there is none", () => {
    expect(defaultPeriod([week("2025-06-23", "2025-06-29", false)])?.periodStart).toBe("2025-06-23");
    expect(defaultPeriod([])).toBeNull();
  });
});

describe("medalClass and scoreColor", () => {
  it("marks the podium", () => {
    expect([1, 2, 3, 4].map(medalClass)).toEqual(["medal-gold", "medal-silver", "medal-bronze", ""]);
  });

  it("colours by score band", () => {
    expect(scoreColor(80)).toBe("#1a9b56");
    expect(scoreColor(50)).toBe("#2f6fed");
    expect(scoreColor(10)).toBe("#9aa3b2");
  });
});
