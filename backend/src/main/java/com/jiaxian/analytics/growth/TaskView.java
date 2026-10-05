package com.jiaxian.analytics.growth;

/** 任务加上当前值（以 asOf 为终点的最近 30 天的指标值）和进度。 */
public record TaskView(GrowthTask task, Double currentValue, Double progress) {
}
