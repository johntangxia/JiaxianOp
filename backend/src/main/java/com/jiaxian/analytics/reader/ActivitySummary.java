package com.jiaxian.analytics.reader;

/** 截至区间结束日的汇总：当天 DAU、近 7 天 WAU、近 30 天 MAU、黏性（近 30 天日均 DAU / MAU）和成员总数。 */
public record ActivitySummary(long dau, long wau, long mau, Double stickiness, long members) {
}
