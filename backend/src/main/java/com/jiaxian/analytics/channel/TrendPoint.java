package com.jiaxian.analytics.channel;

import java.time.LocalDate;

/** 详情页趋势的一个周期：领域频道看按来源拆分的 PV，两类频道都有自己入口的曝光、点击和点击率。 */
public record TrendPoint(LocalDate periodStart, long ownEntry, long compositeEntry, long search, long share,
                         long other, long impressions, long clicks, Double ctr) {
}
