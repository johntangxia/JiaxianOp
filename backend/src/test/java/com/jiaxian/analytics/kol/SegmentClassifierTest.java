package com.jiaxian.analytics.kol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentClassifierTest {

    private static Segment classify(int r, int f, int m, boolean alert, Segment previous, boolean rising) {
        return SegmentClassifier.classify(new RfmScores(r, f, m), alert, previous, rising);
    }

    // ---- 1. 流失预警 ----

    @Test
    void alertAfterStarOrSteadyOrChurnRiskIsChurnRisk() {
        assertThat(classify(1, 1, 1, true, Segment.STAR_KOL, false)).isEqualTo(Segment.CHURN_RISK);
        assertThat(classify(1, 1, 1, true, Segment.STEADY, false)).isEqualTo(Segment.CHURN_RISK);
        assertThat(classify(1, 1, 1, true, Segment.CHURN_RISK, false)).isEqualTo(Segment.CHURN_RISK);
    }

    @Test
    void alertAfterAnyOtherPreviousSegmentIsNotChurnRisk() {
        // 上一期是观察中 → 即使有预警也不算"流失预警"，落到后面的规则（这里 (4,4,3) 不满足新人/沉寂 → 观察中）
        assertThat(classify(4, 4, 3, true, Segment.OBSERVE, false)).isEqualTo(Segment.OBSERVE);
        assertThat(classify(1, 1, 1, true, Segment.DORMANT, false)).isEqualTo(Segment.DORMANT);
    }

    @Test
    void withoutAPreviousPeriodAlertFallsBackToTheAverageScore() {
        assertThat(classify(3, 3, 3, true, null, false)).isEqualTo(Segment.CHURN_RISK);   // avg 3.0
        assertThat(classify(3, 3, 2, true, null, false)).isEqualTo(Segment.OBSERVE);      // avg 2.67
    }

    @Test
    void alertTakesPriorityOverStar() {
        assertThat(classify(5, 5, 5, true, Segment.STAR_KOL, false)).isEqualTo(Segment.CHURN_RISK);
    }

    // ---- 2. 明星 KOL / 4. 稳定贡献者 ----

    @Test
    void starNeedsAverageAtLeastFourAndNoAlert() {
        assertThat(classify(4, 4, 4, false, null, false)).isEqualTo(Segment.STAR_KOL);   // 4.0
        assertThat(classify(5, 5, 5, false, Segment.STAR_KOL, false)).isEqualTo(Segment.STAR_KOL);
    }

    @Test
    void steadyIsAverageBetweenThreeAndFour() {
        assertThat(classify(5, 3, 3, false, null, false)).isEqualTo(Segment.STEADY);   // 3.67
        assertThat(classify(3, 3, 3, false, null, false)).isEqualTo(Segment.STEADY);   // 3.0
        assertThat(classify(4, 4, 3, false, null, false)).isEqualTo(Segment.STEADY);   // 3.67
    }

    @Test
    void alertWithoutQualifyingHistoryNeverMakesAStarOrSteady() {
        assertThat(classify(5, 3, 3, true, Segment.OBSERVE, false)).isEqualTo(Segment.OBSERVE);
    }

    // ---- 3. 成长潜力新人 ----

    @Test
    void risingNeedsHighRecencyLowFrequencyAndMonetaryAndAnUpwardTrend() {
        assertThat(classify(5, 2, 1, false, null, true)).isEqualTo(Segment.RISING);
        assertThat(classify(4, 1, 1, false, null, true)).isEqualTo(Segment.RISING);
        assertThat(classify(5, 2, 1, false, null, false)).isEqualTo(Segment.OBSERVE);   // 趋势没有上升
        assertThat(classify(3, 2, 1, false, null, true)).isEqualTo(Segment.OBSERVE);    // R 不够高
        assertThat(classify(5, 3, 1, false, null, true)).isEqualTo(Segment.STEADY);     // F 不够低 → 不是新人；平均分 3.0 → 稳定贡献者
    }

    @Test
    void risingBeatsSteadyWhenTheAverageIsExactlyThree() {
        // (5,2,2) 平均分恰好 3.0：典型的"近期活跃、产出低、趋势上升"，应判为新人而不是稳定贡献者
        assertThat(classify(5, 2, 2, false, null, true)).isEqualTo(Segment.RISING);
        assertThat(classify(5, 2, 2, false, null, false)).isEqualTo(Segment.STEADY);
    }

    // ---- 5. 沉寂用户 / 6. 观察中 ----

    @Test
    void dormantNeedsAllThreeScoresAtMostTwo() {
        assertThat(classify(1, 1, 1, false, null, false)).isEqualTo(Segment.DORMANT);
        assertThat(classify(2, 2, 2, false, null, false)).isEqualTo(Segment.DORMANT);
        assertThat(classify(2, 2, 3, false, null, false)).isEqualTo(Segment.OBSERVE);
    }

    @Test
    void everythingElseIsObserve() {
        assertThat(classify(3, 3, 2, false, null, false)).isEqualTo(Segment.OBSERVE);   // avg 2.67
        assertThat(classify(1, 4, 3, false, null, false)).isEqualTo(Segment.OBSERVE);   // avg 2.67
    }
}
