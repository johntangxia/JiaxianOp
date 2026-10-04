package com.jiaxian.analytics.kol;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ChurnDetectorTest {

    private static final int RECENT_DAYS = 30;
    private static final int BASELINE_DAYS = 90;
    private static final double THRESHOLD = 0.5;

    private static BloggerMetrics metrics(long recentActivity, long baselineActivity,
                                          long recentPoints, long baselinePoints) {
        return new BloggerMetrics(1, 1, 0, 0, 0, 0,
            recentActivity, baselineActivity, recentPoints, baselinePoints, 0);
    }

    private static Optional<ChurnSignal> detect(BloggerMetrics m) {
        return ChurnDetector.detect(m, RECENT_DAYS, BASELINE_DAYS, THRESHOLD);
    }

    @Test
    void activityDropOverThresholdTriggers() {
        // 基线 180 次 / 90 天 = 2.0 次/天；近期 15 次 / 30 天 = 0.5 次/天 → 下降 0.75；积分持平不触发
        ChurnSignal signal = detect(metrics(15, 180, 300, 900)).orElseThrow();

        assertThat(signal.metric()).isEqualTo(ChurnSignal.Metric.ACTIVITY);
        assertThat(signal.recentDaily()).isCloseTo(0.5, within(1e-9));
        assertThat(signal.baselineDaily()).isCloseTo(2.0, within(1e-9));
        assertThat(signal.dropRate()).isCloseTo(0.75, within(1e-9));
    }

    @Test
    void dropExactlyAtTheThresholdDoesNotTrigger() {
        // 基线 90 次 / 90 天 = 1/天；近期 15 次 / 30 天 = 0.5/天 → 下降恰好 0.5（要求严格大于）
        assertThat(detect(metrics(15, 90, 300, 900))).isEmpty();
    }

    @Test
    void dropJustOverTheThresholdTriggers() {
        // 近期 14 次 / 30 天 = 0.4667/天 → 下降 0.5333
        assertThat(detect(metrics(14, 90, 300, 900))).isPresent();
    }

    @Test
    void zeroBaselineNeverTriggers() {
        assertThat(detect(metrics(0, 0, 0, 0))).isEmpty();
        assertThat(detect(metrics(5, 0, 50, 0))).isEmpty();
    }

    @Test
    void growthDoesNotTrigger() {
        assertThat(detect(metrics(90, 90, 900, 900))).isEmpty();
    }

    @Test
    void pointsDropAloneTriggersWithThePointsMetric() {
        // 活跃次数持平（近期 30/30=1，基线 90/90=1）；积分 基线 9000/90=100/天，近期 600/30=20/天 → 下降 0.8
        ChurnSignal signal = detect(metrics(30, 90, 600, 9000)).orElseThrow();

        assertThat(signal.metric()).isEqualTo(ChurnSignal.Metric.POINTS);
        assertThat(signal.recentDaily()).isCloseTo(20.0, within(1e-9));
        assertThat(signal.baselineDaily()).isCloseTo(100.0, within(1e-9));
        assertThat(signal.dropRate()).isCloseTo(0.8, within(1e-9));
    }

    @Test
    void whenBothTriggerTheLargerDropIsReported() {
        // 活跃：基线 90 (1/天)，近期 6 (0.2/天) → 下降 0.8；积分：基线 9000 (100/天)，近期 300 (10/天) → 下降 0.9
        ChurnSignal signal = detect(metrics(6, 90, 300, 9000)).orElseThrow();

        assertThat(signal.metric()).isEqualTo(ChurnSignal.Metric.POINTS);
        assertThat(signal.dropRate()).isCloseTo(0.9, within(1e-9));
    }

    @Test
    void whenBothDropByTheSameAmountActivityWins() {
        // 两个指标都下降 1.0（近期全是 0）
        ChurnSignal signal = detect(metrics(0, 9, 0, 10)).orElseThrow();

        assertThat(signal.metric()).isEqualTo(ChurnSignal.Metric.ACTIVITY);
        assertThat(signal.dropRate()).isCloseTo(1.0, within(1e-9));
    }
}
