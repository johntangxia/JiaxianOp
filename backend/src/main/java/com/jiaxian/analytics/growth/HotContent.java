package com.jiaxian.analytics.growth;

/** 热点内容：热度倍数 = 近 3 天 PV / 同频道基线；增长倍数 = 近 3 天 PV / 前 3 天 PV（前 3 天按至少 1 算）。 */
public record HotContent(long contentId, long channelId, long pv, long previousPv, double hotMultiple, double growth,
                         Double interactionRate, Double completionRate) {
}
