package com.jiaxian.analytics.people;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserLogicTest {

    private static final LocalDate D = LocalDate.of(2025, 9, 30);
    private static final LocalDate OLD = LocalDate.of(2024, 1, 1);

    // ---- 生命周期 ----

    @Test
    void joinedInsideTheWindowIsNewEvenIfActive() {
        assertThat(LifecycleClassifier.classify(D.minusDays(29), D, List.of(D))).isEqualTo(LifecycleStage.NEW);
        assertThat(LifecycleClassifier.classify(D.minusDays(30), D, List.of(D))).isEqualTo(LifecycleStage.RETURNING);
    }

    @Test
    void activeInBothWindowsIsActive() {
        assertThat(LifecycleClassifier.classify(OLD, D, List.of(D.minusDays(5), D.minusDays(40)))).isEqualTo(LifecycleStage.ACTIVE);
    }

    @Test
    void activeNowButNotBeforeIsReturningEvenOnFirstEverActivity() {
        assertThat(LifecycleClassifier.classify(OLD, D, List.of(D.minusDays(5), D.minusDays(100)))).isEqualTo(LifecycleStage.RETURNING);
        assertThat(LifecycleClassifier.classify(OLD, D, List.of(D.minusDays(5)))).isEqualTo(LifecycleStage.RETURNING);
    }

    @Test
    void activeOnlyInThePreviousWindowIsLapsing() {
        assertThat(LifecycleClassifier.classify(OLD, D, List.of(D.minusDays(30)))).isEqualTo(LifecycleStage.LAPSING);
        assertThat(LifecycleClassifier.classify(OLD, D, List.of(D.minusDays(59)))).isEqualTo(LifecycleStage.LAPSING);
    }

    @Test
    void onlyOlderActivityIsChurnedAndNoActivityIsDormant() {
        assertThat(LifecycleClassifier.classify(OLD, D, List.of(D.minusDays(60)))).isEqualTo(LifecycleStage.CHURNED);
        assertThat(LifecycleClassifier.classify(OLD, D, List.of())).isEqualTo(LifecycleStage.DORMANT);
    }

    @Test
    void activityAfterTheReferenceDayIsIgnoredAndNonMembersAreNull() {
        assertThat(LifecycleClassifier.classify(OLD, D, List.of(D.plusDays(1)))).isEqualTo(LifecycleStage.DORMANT);
        assertThat(LifecycleClassifier.classify(D.plusDays(1), D, List.of())).isNull();
    }

    @Test
    void windowBoundariesAreInclusive() {
        assertThat(LifecycleClassifier.classify(OLD, D, List.of(D.minusDays(29)))).isEqualTo(LifecycleStage.RETURNING);   // W 的第一天
        assertThat(LifecycleClassifier.classify(OLD, D, List.of(D))).isEqualTo(LifecycleStage.RETURNING);
    }

    // ---- 流转矩阵 ----

    @Test
    void transitionMatrixCountsEveryMemberOnceAndRowsSumToPreviousMembers() {
        Map<Long, LifecycleStage> previous = new HashMap<>();
        previous.put(1L, LifecycleStage.ACTIVE);
        previous.put(2L, LifecycleStage.ACTIVE);
        previous.put(3L, LifecycleStage.LAPSING);
        Map<Long, LifecycleStage> current = new HashMap<>();
        current.put(1L, LifecycleStage.ACTIVE);
        current.put(2L, LifecycleStage.LAPSING);
        current.put(3L, LifecycleStage.CHURNED);
        current.put(4L, LifecycleStage.NEW);     // 上个时点还不是成员

        List<TransitionRow> rows = TransitionMatrix.build(previous, current);

        assertThat(rows).hasSize(LifecycleStage.values().length + 1);
        TransitionRow active = rows.stream().filter(r -> r.from() == LifecycleStage.ACTIVE).findFirst().orElseThrow();
        assertThat(active.to().get(LifecycleStage.ACTIVE)).isEqualTo(1);
        assertThat(active.to().get(LifecycleStage.LAPSING)).isEqualTo(1);
        TransitionRow lapsing = rows.stream().filter(r -> r.from() == LifecycleStage.LAPSING).findFirst().orElseThrow();
        assertThat(lapsing.to().get(LifecycleStage.CHURNED)).isEqualTo(1);
        TransitionRow fresh = rows.get(rows.size() - 1);
        assertThat(fresh.from()).isNull();
        assertThat(fresh.to().get(LifecycleStage.NEW)).isEqualTo(1);
        long total = rows.stream().mapToLong(r -> r.to().values().stream().mapToLong(Long::longValue).sum()).sum();
        assertThat(total).isEqualTo(4);
        TransitionRow dormant = rows.stream().filter(r -> r.from() == LifecycleStage.DORMANT).findFirst().orElseThrow();
        assertThat(dormant.to().values()).containsOnly(0L);
    }

    // ---- 价值分层 ----

    @Test
    void rfmClassificationFollowsTheDocumentedOrder() {
        assertThat(ValueSegmenter.classify(5, 5, 5)).isEqualTo(ValueSegment.CORE);
        assertThat(ValueSegmenter.classify(4, 1, 4)).isEqualTo(ValueSegment.CORE);
        assertThat(ValueSegmenter.classify(3, 3, 3)).isEqualTo(ValueSegment.ACTIVE);
        assertThat(ValueSegmenter.classify(4, 1, 3)).isEqualTo(ValueSegment.ACTIVE);
        assertThat(ValueSegmenter.classify(1, 4, 1)).isEqualTo(ValueSegment.TO_WAKE);       // 曾经常来，现在沉下去
        assertThat(ValueSegmenter.classify(2, 1, 3)).isEqualTo(ValueSegment.TO_WAKE);
        assertThat(ValueSegmenter.classify(4, 2, 2)).isEqualTo(ValueSegment.LOW_ACTIVE);
        assertThat(ValueSegmenter.classify(3, 1, 1)).isEqualTo(ValueSegment.LOW_ACTIVE);
        assertThat(ValueSegmenter.classify(1, 1, 1)).isEqualTo(ValueSegment.SLEEPING);
        assertThat(ValueSegmenter.classify(2, 2, 2)).isEqualTo(ValueSegment.SLEEPING);
    }

    @Test
    void percentilesMapToOneToFiveScores() {
        assertThat(ValueSegmenter.toScore(0)).isEqualTo(1);
        assertThat(ValueSegmenter.toScore(19.9)).isEqualTo(1);
        assertThat(ValueSegmenter.toScore(20)).isEqualTo(2);
        assertThat(ValueSegmenter.toScore(79.9)).isEqualTo(4);
        assertThat(ValueSegmenter.toScore(80)).isEqualTo(5);
        assertThat(ValueSegmenter.toScore(100)).isEqualTo(5);
    }

    @Test
    void segmentRanksPeopleAgainstEachOtherAndGivesNoDataOneEverywhere() {
        List<ValueInput> inputs = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            // 员工 10 最近、最常来、最投入；员工 1 最差但有数据
            inputs.add(new ValueInput(i, 60 - i * 5, i, i * 10.0));
        }
        inputs.add(new ValueInput(99, null, 0, 0));

        Map<Long, ValueScore> scores = new HashMap<>();
        ValueSegmenter.segment(inputs).forEach(s -> scores.put(s.employeeId(), s));

        assertThat(scores).hasSize(11);
        assertThat(scores.get(10L).segment()).isEqualTo(ValueSegment.CORE);
        assertThat(scores.get(10L).recency()).isEqualTo(5);
        assertThat(scores.get(99L).recency()).isEqualTo(1);
        assertThat(scores.get(99L).frequency()).isEqualTo(1);
        assertThat(scores.get(99L).monetary()).isEqualTo(1);
        assertThat(scores.get(99L).segment()).isEqualTo(ValueSegment.SLEEPING);
        assertThat(scores.get(1L).recency()).isLessThan(scores.get(10L).recency());
    }

    @Test
    void segmentOfAnEmptyListIsEmpty() {
        assertThat(ValueSegmenter.segment(List.of())).isEmpty();
    }
}
