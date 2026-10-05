package com.jiaxian.analytics.growth;

import java.time.LocalDate;

/** 效果趋势的一个点：以 windowEnd 结束的 7 天窗口里的指标值；containsStart 表示任务开始日落在这个窗口里。 */
public record EffectPoint(LocalDate windowEnd, Double value, boolean containsStart) {
}
