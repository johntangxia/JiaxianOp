package com.jiaxian.analytics.contest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ContestScorerTest {

    private static Candidate c(long id, Double a, Double b) {
        java.util.HashMap<String, Double> metrics = new java.util.HashMap<>();
        metrics.put("A", a);
        metrics.put("B", b);
        return new Candidate(id, metrics);
    }

    private static final DimensionDef ONLY_A = new DimensionDef("A", "指标A", 1.0, List.of("A"));

    @Test
    void firstPlaceGetsHundredLastGetsZeroAndOthersAreEvenlyInBetween() {
        double[] scores = ContestScorer.percentiles(new double[] {10, 30, 20, 40});

        assertThat(scores).containsExactly(new double[] {0, 200.0 / 3, 100.0 / 3, 100}, within(1e-9));
    }

    @Test
    void tiesShareTheAverageOfTheirPositions() {
        // 值 [5, 5, 1]：1 → 0；两个 5 占据第 2、3 名位置 → 各 (1 + 0.5) / 2 × 100 = 75
        assertThat(ContestScorer.percentiles(new double[] {5, 5, 1})).containsExactly(75, 75, 0);
    }

    @Test
    void channelsWithoutDataScoreZeroInsteadOfSharingTheBottomTie() {
        // 两个频道没有数据：它们不能因为并列而各得一部分分数；有数据的按榜内百分位（0 值算最低）
        double[] scores = ContestScorer.percentiles(new double[] {0, 0, 3, 5});

        assertThat(scores).containsExactly(new double[] {0, 0, 200.0 / 3, 100}, within(1e-9));
    }

    @Test
    void anAllZeroMetricGivesEveryoneZeroInsteadOfFifty() {
        assertThat(ContestScorer.percentiles(new double[] {0, 0, 0})).containsExactly(0, 0, 0);
    }

    @Test
    void aSingleChannelGetsHundredOnlyIfItHasData() {
        assertThat(ContestScorer.percentiles(new double[] {7})).containsExactly(100);
        assertThat(ContestScorer.percentiles(new double[] {0})).containsExactly(0);
    }

    @Test
    void missingValuesCountAsZeroAndRankAtTheBottom() {
        List<ScoredChannel> scored = ContestScorer.score(
            List.of(c(1, 5.0, null), c(2, null, null), c(3, 9.0, null)), List.of(ONLY_A));

        assertThat(scored.get(0).total()).isEqualTo(50);
        assertThat(scored.get(1).total()).isEqualTo(0);
        assertThat(scored.get(2).total()).isEqualTo(100);
    }

    @Test
    void aDimensionAveragesItsMetricsAndTheTotalIsTheWeightedSum() {
        DimensionDef both = new DimensionDef("AB", "组合", 0.25, List.of("A", "B"));
        DimensionDef onlyB = new DimensionDef("B", "指标B", 0.75, List.of("B"));
        // A: 通道1=1 通道2=2 → 0 / 100；B: 通道1=2 通道2=1 → 100 / 0
        List<ScoredChannel> scored = ContestScorer.score(List.of(c(1, 1.0, 2.0), c(2, 2.0, 1.0)), List.of(both, onlyB));

        ScoredChannel first = scored.get(0);
        assertThat(first.dimensions().get(0).score()).isEqualTo(50);   // (0 + 100) / 2
        assertThat(first.dimensions().get(1).score()).isEqualTo(100);
        assertThat(first.total()).isCloseTo(0.25 * 50 + 0.75 * 100, within(1e-9));
        ScoredChannel second = scored.get(1);
        assertThat(second.total()).isCloseTo(0.25 * 50 + 0.75 * 0, within(1e-9));
        assertThat(first.dimensions().get(0).weight()).isEqualTo(0.25);
        assertThat(first.dimensions().get(0).label()).isEqualTo("组合");
    }

    @Test
    void resultKeepsTheCandidateOrder() {
        List<ScoredChannel> scored = ContestScorer.score(List.of(c(9, 1.0, 1.0), c(3, 2.0, 2.0)), List.of(ONLY_A));

        assertThat(scored).extracting(ScoredChannel::channelId).containsExactly(9L, 3L);
    }

    @Test
    void ranksAreStandardCompetitionRanksWithTiesBrokenByChannelIdOnlyForOrdering() {
        List<ScoredChannel> scored = List.of(
            new ScoredChannel(1, 80, List.of()), new ScoredChannel(2, 90, List.of()),
            new ScoredChannel(3, 80, List.of()), new ScoredChannel(4, 10, List.of()));

        Map<Long, Integer> ranks = ContestScorer.ranks(scored);

        assertThat(ranks).containsEntry(2L, 1).containsEntry(1L, 2).containsEntry(3L, 2).containsEntry(4L, 4);
    }

    @Test
    void emptyInputIsHandled() {
        assertThat(ContestScorer.score(List.of(), List.of(ONLY_A))).isEmpty();
        assertThat(ContestScorer.ranks(List.of())).isEmpty();
    }
}
