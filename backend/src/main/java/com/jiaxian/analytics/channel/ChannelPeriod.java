package com.jiaxian.analytics.channel;

/** 一个频道在一个周期里的全部指标：综合频道的 content/supply 为 null，领域频道的 coverage 为 null。 */
public record ChannelPeriod(DistributionMetrics distribution, ContentMetrics content, SupplyMetrics supply,
                            CompositeCoverage coverage) {
}
