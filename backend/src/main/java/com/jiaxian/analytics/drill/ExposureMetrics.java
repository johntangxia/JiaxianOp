package com.jiaxian.analytics.drill;

/**
 * 这些内容在各入口获得的曝光、点击和点击率（注意与频道驾驶舱不同：驾驶舱是"某个入口展示了什么"，
 * 这里是"这些内容被展示了多少"），以及平均停留和读完/播完率。没有数据的比例为 null。
 */
public record ExposureMetrics(long impressions, long clicks, Double ctr, Double avgDwellSeconds, Double completionRate) {

    public static final ExposureMetrics EMPTY = new ExposureMetrics(0, 0, null, null, null);
}
