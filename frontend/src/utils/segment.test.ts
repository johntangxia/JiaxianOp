import { describe, expect, it } from "vitest";
import { SEGMENTS, segmentName, segmentTagType } from "./segment";

describe("segment dictionary", () => {
  it("covers all six segments with unique Chinese names", () => {
    expect(SEGMENTS.map((s) => s.value)).toEqual([
      "STAR_KOL", "STEADY", "RISING", "CHURN_RISK", "DORMANT", "OBSERVE",
    ]);
    expect(new Set(SEGMENTS.map((s) => s.name)).size).toBe(6);
  });

  it("names and colors the segments", () => {
    expect(segmentName("STAR_KOL")).toBe("明星KOL");
    expect(segmentName("CHURN_RISK")).toBe("流失预警");
    expect(segmentTagType("CHURN_RISK")).toBe("danger");
    expect(segmentTagType("STEADY")).toBe("success");
  });
});
