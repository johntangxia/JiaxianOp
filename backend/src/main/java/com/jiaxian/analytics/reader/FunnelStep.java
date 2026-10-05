package com.jiaxian.analytics.reader;

/** 漏斗的一步：人数和占加入人数的比例（没有新成员时为 null）。 */
public record FunnelStep(String key, String label, long count, Double rateOfJoined) {
}
