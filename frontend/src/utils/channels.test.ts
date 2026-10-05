import { describe, expect, it } from "vitest";
import type { ChannelOption } from "../api/types";
import { CATEGORY_COMPOSITE, CATEGORY_DOMAIN, domainChannels } from "./channels";

const channels: ChannelOption[] = [
  { id: 1, name: "推荐", category: CATEGORY_COMPOSITE },
  { id: 2, name: "半导体", category: CATEGORY_DOMAIN },
  { id: 3, name: "移动无线", category: "已停用" },
  { id: 4, name: "光", category: CATEGORY_DOMAIN },
];

describe("domainChannels", () => {
  it("keeps only domain channels and preserves their order", () => {
    expect(domainChannels(channels).map((c) => c.name)).toEqual(["半导体", "光"]);
  });

  it("returns an empty list when there are no domain channels", () => {
    expect(domainChannels([channels[0]])).toEqual([]);
  });
});
