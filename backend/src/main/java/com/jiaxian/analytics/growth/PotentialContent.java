package com.jiaxian.analytics.growth;

/** 潜力内容：PV 不高但互动率或读完率明显高于同频道；质量倍数取两者倍数中较大的。 */
public record PotentialContent(long contentId, long channelId, long pv, Double interactionRate, Double completionRate,
                               double qualityMultiple) {
}
