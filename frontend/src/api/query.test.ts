import { describe, expect, it } from "vitest";
import { buildQuery } from "./query";

describe("buildQuery", () => {
  it("returns an empty string when there are no usable params", () => {
    expect(buildQuery()).toBe("");
    expect(buildQuery({ a: undefined, b: null, c: "" })).toBe("");
  });

  it("skips empty values and keeps numbers, booleans and strings", () => {
    expect(buildQuery({ a: 1, b: "x", c: false, d: undefined, e: 0 })).toBe("?a=1&b=x&c=false&e=0");
  });

  it("encodes special characters", () => {
    expect(buildQuery({ keyword: "张 三&1" })).toBe("?keyword=%E5%BC%A0+%E4%B8%89%261");
  });
});
