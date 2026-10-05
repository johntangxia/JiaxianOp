package com.jiaxian.analytics.channel;

/** 期间内发布的内容数和去重作者数。 */
public record SupplyMetrics(long contents, long authors) {

    public static final SupplyMetrics EMPTY = new SupplyMetrics(0, 0);
}
