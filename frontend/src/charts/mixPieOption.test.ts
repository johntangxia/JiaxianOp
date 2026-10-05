import { describe, expect, it } from "vitest";
import { buildMixPieOption } from "./mixPieOption";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const data = (option: any): any[] => option.series[0].data;

describe("buildMixPieOption", () => {
  it("drops zero-valued slices", () => {
    const option = buildMixPieOption([
      { key: "OWN_ENTRY", label: "自主入口", value: 5 },
      { key: "SEARCH", label: "搜索", value: 0 },
    ]);

    expect(data(option).map((d) => d.name)).toEqual(["自主入口"]);
  });

  it("uses fixed colours for the known source keys and the default palette otherwise", () => {
    const option = buildMixPieOption([
      { key: "OWN_ENTRY", label: "自主入口", value: 5 },
      { key: "12", label: "半导体", value: 3 },
    ]);

    expect(data(option)[0].itemStyle.color).toBe("#2f6fed");
    expect(data(option)[1].itemStyle).toBeUndefined();
  });

  it("handles an empty list", () => {
    expect(data(buildMixPieOption([]))).toEqual([]);
  });
});
