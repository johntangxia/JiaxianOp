package com.jiaxian.analytics.drill;

import java.time.LocalDate;

/**
 * 下钻页趋势的一个周期：按来源拆分的 PV、这些内容获得的曝光/点击/点击率，以及（账号才有的）新增/取关关注者。
 * 字段名与频道详情的 TrendPoint 一致，前端可以共用同一个趋势图。
 */
public record DrillTrendPoint(LocalDate periodStart, long ownEntry, long compositeEntry, long search, long share,
                              long other, long impressions, long clicks, Double ctr, long newFollowers,
                              long lostFollowers) {
}
