package com.jiaxian.analytics.kol;

/** 一次触发的流失预警：哪个指标、近期日均、基线日均、下降比例。 */
public record ChurnSignal(Metric metric, double recentDaily, double baselineDaily, double dropRate) {

    public enum Metric { ACTIVITY, POINTS }
}
