package com.jiaxian.analytics.kol;

import java.util.Optional;

/**
 * 纯逻辑：流失预警。近期和基线折算成日均，活跃次数和积分两个指标分别判断；
 * 基线日均为 0 的指标不参与；任一指标下降比例严格大于阈值即触发，两个都触发时记下降更多的，并列记活跃次数。
 */
public final class ChurnDetector {

    private ChurnDetector() {
    }

    public static Optional<ChurnSignal> detect(BloggerMetrics metrics, int recentDays, int baselineDays,
                                               double dropThreshold) {
        Optional<ChurnSignal> activity = signal(ChurnSignal.Metric.ACTIVITY,
            metrics.recentActivity(), metrics.baselineActivity(), recentDays, baselineDays, dropThreshold);
        Optional<ChurnSignal> points = signal(ChurnSignal.Metric.POINTS,
            metrics.recentPoints(), metrics.baselinePoints(), recentDays, baselineDays, dropThreshold);

        if (activity.isPresent() && points.isPresent()) {
            return points.get().dropRate() > activity.get().dropRate() ? points : activity;
        }
        return activity.isPresent() ? activity : points;
    }

    private static Optional<ChurnSignal> signal(ChurnSignal.Metric metric, long recentTotal, long baselineTotal,
                                                int recentDays, int baselineDays, double dropThreshold) {
        double baselineDaily = baselineTotal / (double) baselineDays;
        if (baselineDaily <= 0) {
            return Optional.empty();
        }
        double recentDaily = recentTotal / (double) recentDays;
        double dropRate = 1 - recentDaily / baselineDaily;
        if (dropRate > dropThreshold) {
            return Optional.of(new ChurnSignal(metric, recentDaily, baselineDaily, dropRate));
        }
        return Optional.empty();
    }
}
