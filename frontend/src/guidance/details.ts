import { channelDetails } from './details/channels';
import { creatorDetails } from './details/creators';
import { kolDetails } from './details/kol';
import { otherDetails } from './details/other';
import { readerDetails } from './details/readers';
import { userDetails } from './details/users';
import type { GuidanceDetail } from './details/types';

const details: Record<string, GuidanceDetail> = {
  ...channelDetails, ...creatorDetails, ...kolDetails, ...otherDetails, ...readerDetails, ...userDetails,
};

export function getGuidanceDetail(id: string): GuidanceDetail {
  const result = details[id];
  if (!result) throw new Error(`缺少说明详情: ${id}`);
  return result;
}
