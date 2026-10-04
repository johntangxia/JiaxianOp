package com.jiaxian.analytics.kol;

/** 触发流失预警时的详情：触发的指标、近期日均、基线日均、下降比例。 */
public record AlertDetail(ChurnSignal.Metric metric, double recentDaily, double baselineDaily, double dropRate) {
}
