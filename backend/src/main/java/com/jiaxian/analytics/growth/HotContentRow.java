package com.jiaxian.analytics.growth;

import java.time.LocalDateTime;

/** 热点或潜力内容的一行，带内容、频道、账号信息。hotMultiple/growth 只有热点有，qualityMultiple 只有潜力有。 */
public record HotContentRow(long contentId, String type, long channelId, String channelName, long accountId,
                            String accountName, LocalDateTime publishTime, long pv, Long previousPv, Double hotMultiple,
                            Double growth, Double qualityMultiple, Double interactionRate, Double completionRate) {
}
