package com.jiaxian.analytics.channel;

/**
 * 一个频道"自己入口的分发"：综合频道和领域频道口径相同，都来自曝光表（入口频道 = 该频道）。
 *
 * @param ctr             点击 / 曝光，没有曝光时为 null
 * @param reachUv         看到过内容的去重员工数
 * @param clickUv         点击过内容的去重员工数
 * @param avgDwellSeconds 入口来源浏览的平均停留秒数，没有浏览时为 null
 */
public record DistributionMetrics(long impressions, long clicks, Double ctr, long reachUv, long clickUv,
                                  Double avgDwellSeconds) {

    public static final DistributionMetrics EMPTY = new DistributionMetrics(0, 0, null, 0, 0, null);
}
