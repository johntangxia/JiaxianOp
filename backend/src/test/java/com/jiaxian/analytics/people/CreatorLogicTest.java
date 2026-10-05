package com.jiaxian.analytics.people;

import com.jiaxian.analytics.pv.Granularity;
import com.jiaxian.analytics.reader.CohortRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CreatorLogicTest {

    private static final LocalDate MON = LocalDate.of(2025, 6, 2);

    // ---- 基尼系数与中位数 ----

    @Test
    void giniIsZeroWhenEveryoneIsEqualAndGrowsWithConcentration() {
        assertThat(GiniCalculator.gini(List.of(5.0, 5.0, 5.0, 5.0))).isCloseTo(0.0, within(1e-9));
        // 一个人拿走全部：n=4 时基尼 = (n-1)/n = 0.75
        assertThat(GiniCalculator.gini(List.of(0.0, 0.0, 0.0, 10.0))).isCloseTo(0.75, within(1e-9));
        assertThat(GiniCalculator.gini(List.of(1.0, 2.0, 3.0, 4.0))).isCloseTo(0.25, within(1e-9));
    }

    @Test
    void giniHandlesEmptySingleAndAllZeroInputs() {
        assertThat(GiniCalculator.gini(List.of())).isNull();
        assertThat(GiniCalculator.gini(List.of(0.0, 0.0))).isNull();
        assertThat(GiniCalculator.gini(List.of(7.0))).isEqualTo(0.0);
    }

    @Test
    void medianOfOddEvenAndEmptyLists() {
        assertThat(MedianCalculator.median(List.of(5L, 1L, 3L))).isEqualTo(3.0);
        assertThat(MedianCalculator.median(List.of(4, 1, 2, 3))).isEqualTo(2.5);
        assertThat(MedianCalculator.median(List.<Long>of())).isNull();
    }

    // ---- 集中度 ----

    private static CreatorFigures c(long id, long posts, long pv) {
        return new CreatorFigures(id, posts, pv);
    }

    @Test
    void concentrationTakesTheTopSharesByPv() {
        // 10 个创作者：PV 100, 50, 10×8 = 230；发帖都是 2
        List<CreatorFigures> creators = new java.util.ArrayList<>(List.of(c(1, 2, 100), c(2, 2, 50)));
        for (long id = 3; id <= 10; id++) {
            creators.add(c(id, 2, 10));
        }

        List<ConcentrationShare> shares = ConcentrationBuilder.build(creators);

        assertThat(shares).extracting(ConcentrationShare::label).containsExactly("前 1 名", "前 5 名", "前 10%", "前 20%");
        assertThat(shares.get(0).pvShare()).isCloseTo(100 / 230.0, within(1e-9));
        assertThat(shares.get(0).postShare()).isCloseTo(0.1, within(1e-9));
        assertThat(shares.get(1).creators()).isEqualTo(5);
        assertThat(shares.get(1).pvShare()).isCloseTo((100 + 50 + 30) / 230.0, within(1e-9));
        assertThat(shares.get(2).creators()).isEqualTo(1);      // 10 人的 10% = 1 人
        assertThat(shares.get(3).creators()).isEqualTo(2);      // 20% = 2 人
        assertThat(shares.get(3).pvShare()).isCloseTo(150 / 230.0, within(1e-9));
    }

    @Test
    void concentrationWithFewOrNoCreators() {
        List<ConcentrationShare> one = ConcentrationBuilder.build(List.of(c(1, 3, 40)));
        assertThat(one).extracting(ConcentrationShare::creators).containsExactly(1, 1, 1, 1);
        assertThat(one).extracting(ConcentrationShare::pvShare).containsOnly(1.0);

        List<ConcentrationShare> none = ConcentrationBuilder.build(List.of());
        assertThat(none).extracting(ConcentrationShare::creators).containsOnly(0);
        assertThat(none).extracting(ConcentrationShare::pvShare).containsOnlyNulls();

        List<ConcentrationShare> noPv = ConcentrationBuilder.build(List.of(c(1, 2, 0), c(2, 1, 0)));
        assertThat(noPv.get(0).pvShare()).isNull();
        assertThat(noPv.get(0).postShare()).isNotNull();
    }

    // ---- 成长阶梯阶段 ----

    private static LifetimeFacts facts(Integer view, Integer interaction, Integer post, long recentPosts) {
        LocalDateTime base = LocalDateTime.of(2025, 1, 1, 9, 0);
        LocalDateTime first = base;
        return new LifetimeFacts(1, first, view == null ? null : base.plusDays(view), interaction == null ? null : base.plusDays(interaction),
            post == null ? null : base.plusDays(post), recentPosts, base.plusDays(100));
    }

    @Test
    void stageIsTheHighestOneReached() {
        assertThat(CreatorStageClassifier.classify(null, false)).isEqualTo(CreatorStage.INACTIVE);
        assertThat(CreatorStageClassifier.classify(new LifetimeFacts(1, null, null, null, null, 0, null), false)).isEqualTo(CreatorStage.INACTIVE);
        assertThat(CreatorStageClassifier.classify(facts(1, null, null, 0), false)).isEqualTo(CreatorStage.READER);
        assertThat(CreatorStageClassifier.classify(facts(1, 2, null, 0), false)).isEqualTo(CreatorStage.INTERACTOR);
        assertThat(CreatorStageClassifier.classify(facts(1, 2, 3, 2), false)).isEqualTo(CreatorStage.CREATOR);
        assertThat(CreatorStageClassifier.classify(facts(1, 2, 3, 3), false)).isEqualTo(CreatorStage.ACTIVE_CREATOR);
        assertThat(CreatorStageClassifier.classify(facts(1, 2, 3, 9), true)).isEqualTo(CreatorStage.KOL);
        assertThat(CreatorStage.ACTIVE_CREATOR.label()).isEqualTo("活跃创作者");
    }

    @Test
    void aPostWithoutInteractionStillMakesACreator() {
        assertThat(CreatorStageClassifier.classify(facts(null, null, 5, 0), false)).isEqualTo(CreatorStage.CREATOR);
    }

    // ---- 周期序列与窗口对比 ----

    private static PostDay p(long id, LocalDate day, long posts) {
        return new PostDay(id, day, posts);
    }

    @Test
    void seriesCountsDistinctCreatorsNewCreatorsAndPosts() {
        Map<Long, LocalDate> first = Map.of(1L, MON.minusDays(40), 2L, MON.plusDays(1), 3L, MON.plusDays(8));
        List<PostDay> days = List.of(p(1, MON, 2), p(1, MON.plusDays(2), 1), p(2, MON.plusDays(1), 4), p(3, MON.plusDays(8), 1),
            p(1, MON.minusDays(1), 9));          // 区间外的发帖不算

        List<CreatorPoint> series = CreatorActivity.series(days, first, MON, MON.plusDays(13), Granularity.WEEK);

        assertThat(series).extracting(CreatorPoint::periodStart).containsExactly(MON, MON.plusWeeks(1));
        assertThat(series).extracting(CreatorPoint::activeCreators).containsExactly(2L, 1L);
        assertThat(series).extracting(CreatorPoint::newCreators).containsExactly(1L, 1L);
        assertThat(series).extracting(CreatorPoint::posts).containsExactly(7L, 1L);
        assertThat(series.get(0).postsPerCreator()).isEqualTo(3.5);
    }

    @Test
    void seriesHasZeroFilledPeriodsWithoutAPerCreatorAverage() {
        List<CreatorPoint> series = CreatorActivity.series(List.of(), Map.of(), MON, MON.plusDays(6), Granularity.DAY);

        assertThat(series).hasSize(7);
        assertThat(series).allMatch(point -> point.activeCreators() == 0 && point.postsPerCreator() == null);
    }

    @Test
    void summaryFindsNewLostAndReturningCreators() {
        LocalDate from = LocalDate.of(2025, 6, 1);
        LocalDate to = LocalDate.of(2025, 6, 30);
        LocalDate previousFrom = LocalDate.of(2025, 5, 2);
        LocalDate previousTo = LocalDate.of(2025, 5, 31);
        Map<Long, LocalDate> first = new HashMap<>();
        first.put(1L, LocalDate.of(2025, 3, 1));    // 一直在发
        first.put(2L, LocalDate.of(2025, 3, 1));    // 上期发过、本期没发 → 流失
        first.put(3L, LocalDate.of(2025, 3, 1));    // 更早发过、上期没发、本期发了 → 回流
        first.put(4L, LocalDate.of(2025, 6, 10));   // 本期首次 → 新增
        List<PostDay> days = List.of(p(1, LocalDate.of(2025, 5, 10), 1), p(1, LocalDate.of(2025, 6, 10), 2),
            p(2, LocalDate.of(2025, 5, 12), 1), p(3, LocalDate.of(2025, 6, 11), 3), p(4, LocalDate.of(2025, 6, 10), 1));

        CreatorSummary summary = CreatorActivity.summary(days, first, from, to, previousFrom, previousTo, 100);

        assertThat(summary.creators()).isEqualTo(3);
        assertThat(summary.newCreators()).isEqualTo(1);
        assertThat(summary.lostCreators()).isEqualTo(1);
        assertThat(summary.returningCreators()).isEqualTo(1);
        assertThat(summary.creatorShare()).isEqualTo(0.03);
        assertThat(summary.posts()).isEqualTo(6);
        assertThat(summary.previousCreators()).isEqualTo(2);
        assertThat(summary.previousPosts()).isEqualTo(2);
    }

    @Test
    void summaryWithoutMembersHasNoShare() {
        CreatorSummary summary = CreatorActivity.summary(List.of(), Map.of(), MON, MON.plusDays(29), MON.minusDays(30), MON.minusDays(1), 0);

        assertThat(summary.creatorShare()).isNull();
        assertThat(summary.creators()).isZero();
    }

    // ---- 创作者留存 ----

    @Test
    void retentionCountsCreatorsWhoPostAgainInLaterMonths() {
        // 两个人 3 月首次发帖：员工 1 在 4 月又发了，员工 2 没有；员工 3 在 4 月首次发帖
        Map<Long, LocalDate> first = Map.of(1L, LocalDate.of(2025, 3, 5), 2L, LocalDate.of(2025, 3, 20), 3L, LocalDate.of(2025, 4, 2));
        List<PostDay> days = List.of(p(1, LocalDate.of(2025, 3, 5), 1), p(2, LocalDate.of(2025, 3, 20), 1),
            p(1, LocalDate.of(2025, 4, 8), 1), p(3, LocalDate.of(2025, 4, 2), 1));

        List<CohortRow> cohorts = CreatorRetention.build(first, days, LocalDate.of(2025, 3, 1), LocalDate.of(2025, 4, 30), 3,
            LocalDate.of(2025, 5, 15));

        assertThat(cohorts).extracting(CohortRow::cohortStart).containsExactly(LocalDate.of(2025, 3, 1), LocalDate.of(2025, 4, 1));
        assertThat(cohorts.get(0).size()).isEqualTo(2);
        assertThat(cohorts.get(1).size()).isEqualTo(1);
        assertThat(cohorts.get(1).retention()).containsExactly(1.0, null, null);
        assertThat(cohorts.get(0).retention()).containsExactly(1.0, 0.5, null);       // 5 月在数据最后一天（5/15）之后才结束，还没覆盖
    }
}
