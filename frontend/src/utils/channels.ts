import type { ChannelOption } from "../api/types";

export const CATEGORY_COMPOSITE = "综合频道";
export const CATEGORY_DOMAIN = "领域频道";

/** PV 按内容所属频道统计，而内容只产生在领域频道，所以筛选框里只列领域频道。 */
export function domainChannels(channels: ChannelOption[]): ChannelOption[] {
  return channels.filter((c) => c.category === CATEGORY_DOMAIN);
}
