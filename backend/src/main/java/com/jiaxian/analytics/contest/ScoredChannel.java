package com.jiaxian.analytics.contest;

import java.util.List;

/** 一个频道的健康分（0–100）和各维度得分。 */
public record ScoredChannel(long channelId, double total, List<DimensionScore> dimensions) {
}
