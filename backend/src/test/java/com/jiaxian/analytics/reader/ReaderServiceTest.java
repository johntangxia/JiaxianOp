package com.jiaxian.analytics.reader;

import com.jiaxian.analytics.pv.Granularity;
import com.jiaxian.analytics.reader.ReaderQueryRepository.Lurker;
import com.jiaxian.analytics.reader.ReaderQueryRepository.LurkerPage;
import com.jiaxian.analytics.reader.ReaderQueryRepository.MemberActivity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReaderServiceTest {

    private static final LocalDate FROM = LocalDate.of(2025, 6, 2);
    private static final LocalDate TO = LocalDate.of(2025, 6, 29);

    private final ReaderQueryRepository repository = mock(ReaderQueryRepository.class);
    private final ReaderService service = new ReaderService(repository);

    private static Member member(long id, LocalDate join) {
        return new Member(id, "员工" + id, "部门", join);
    }

    // ---- 校验 ----

    @Test
    void rejectsAnInvertedOrTooLongRange() {
        assertThatThrownBy(() -> service.activity(TO, FROM, Granularity.DAY)).isInstanceOf(InvalidReaderQueryException.class);
        assertThatThrownBy(() -> service.funnel(FROM, FROM.plusDays(366)))
            .isInstanceOf(InvalidReaderQueryException.class).hasMessageContaining("366");
        assertThat(service.funnel(FROM, FROM.plusDays(365))).isNotNull();
    }

    // ---- 活跃 ----

    @Test
    void activityFetchesThirtyDaysBeforeToWhenTheRangeIsShorterSoMauIsComplete() {
        when(repository.members()).thenReturn(List.of(member(1, null), member(2, null)));
        when(repository.activeDays(any(), any())).thenReturn(List.of(
            new ActiveDay(1, TO), new ActiveDay(2, TO.minusDays(20))));
        LocalDate shortFrom = TO.minusDays(6);

        ActivityReport report = service.activity(shortFrom, TO, Granularity.DAY);

        verify(repository).activeDays(TO.minusDays(29), TO);
        assertThat(report.summary().mau()).isEqualTo(2);
        assertThat(report.summary().dau()).isEqualTo(1);
        assertThat(report.points()).hasSize(7);
        // 区间外（06-09 之前）的活跃日不进趋势
        assertThat(report.points().stream().mapToLong(ActivityPoint::activeUsers).sum()).isEqualTo(1);
    }

    @Test
    void activityKeepsTheRangeStartWhenItIsLongerThanThirtyDays() {
        when(repository.members()).thenReturn(List.of());
        when(repository.activeDays(any(), any())).thenReturn(List.of());

        service.activity(FROM.minusDays(40), TO, Granularity.WEEK);

        verify(repository).activeDays(FROM.minusDays(40), TO);
    }

    // ---- 留存 ----

    @Test
    void retentionDefaultsToEightWeeksOrSixMonths() {
        when(repository.latestActiveDay()).thenReturn(Optional.of(TO));
        when(repository.activeDays(any(), any())).thenReturn(List.of());
        when(repository.members()).thenReturn(List.of());

        assertThat(service.retention(Granularity.WEEK, FROM, TO, null).periods()).isEqualTo(8);
        assertThat(service.retention(Granularity.MONTH, FROM, TO, null).periods()).isEqualTo(6);
        assertThat(service.retention(Granularity.MONTH, FROM, TO, 12).periods()).isEqualTo(12);
    }

    @Test
    void retentionRejectsOtherGranularitiesAndBadPeriodCounts() {
        assertThatThrownBy(() -> service.retention(Granularity.DAY, FROM, TO, null)).isInstanceOf(InvalidReaderQueryException.class);
        assertThatThrownBy(() -> service.retention(Granularity.YEAR, FROM, TO, null)).isInstanceOf(InvalidReaderQueryException.class);
        assertThatThrownBy(() -> service.retention(Granularity.WEEK, FROM, TO, 0)).isInstanceOf(InvalidReaderQueryException.class);
        assertThatThrownBy(() -> service.retention(Granularity.WEEK, FROM, TO, 13)).isInstanceOf(InvalidReaderQueryException.class);
    }

    @Test
    void retentionBuildsCohortsAndUsesTheLatestActiveDayAsDataEnd() {
        LocalDate join = LocalDate.of(2025, 6, 3);
        when(repository.members()).thenReturn(List.of(member(1, join), member(2, join)));
        when(repository.latestActiveDay()).thenReturn(Optional.of(LocalDate.of(2025, 6, 15)));
        when(repository.activeDays(FROM, LocalDate.of(2025, 6, 15))).thenReturn(List.of(
            new ActiveDay(1, join), new ActiveDay(2, join), new ActiveDay(1, LocalDate.of(2025, 6, 10))));

        RetentionReport report = service.retention(Granularity.WEEK, FROM, TO, 3);

        assertThat(report.dataEnd()).isEqualTo(LocalDate.of(2025, 6, 15));
        assertThat(report.cohorts()).hasSize(1);
        assertThat(report.cohorts().get(0).size()).isEqualTo(2);
        assertThat(report.cohorts().get(0).retention()).containsExactly(1.0, 0.5, null);   // 06-16 那周还没结束
    }

    @Test
    void retentionWithoutAnyActivityLeavesEverythingEmpty() {
        when(repository.members()).thenReturn(List.of(member(1, FROM)));
        when(repository.latestActiveDay()).thenReturn(Optional.empty());

        RetentionReport report = service.retention(Granularity.WEEK, FROM, TO, 2);

        assertThat(report.cohorts().get(0).retention()).containsExactly(null, null);
    }

    // ---- 漏斗 ----

    @Test
    void funnelCountsTheJoinedMembersAndTheirSteps() {
        LocalDate join = LocalDate.of(2025, 6, 3);
        when(repository.funnelFacts(FROM, TO)).thenReturn(List.of(
            new FunnelFacts(1, join, join.atStartOfDay().plusHours(2), null, null, true),
            new FunnelFacts(2, join, null, null, null, false)));

        FunnelReport report = service.funnel(FROM, TO);

        assertThat(report.joined()).isEqualTo(2);
        assertThat(report.steps()).extracting(FunnelStep::count).containsExactly(2L, 1L, 0L, 0L, 1L);
    }

    // ---- 分层 ----

    @Test
    void segmentsClassifyEveryMemberAndSumToTheMemberCount() {
        when(repository.members()).thenReturn(List.of(
            member(1, null), member(2, null), member(3, null), member(4, null), member(5, null), member(6, null),
            member(7, TO.plusDays(1))));   // 7 在区间结束后才加入，不算成员
        LocalDate day = LocalDate.of(2025, 6, 10);
        when(repository.memberActivity(FROM, TO)).thenReturn(List.of(
            new MemberActivity(1, 1, 0, 5, 6, 3, day),
            new MemberActivity(2, 0, 2, 5, 7, 3, day),
            new MemberActivity(3, 0, 0, 5, 5, 3, day),
            new MemberActivity(4, 0, 0, 0, 2, 2, day),
            new MemberActivity(7, 0, 0, 9, 9, 1, day)));

        SegmentReport report = service.segments(FROM, TO);

        assertThat(report.members()).isEqualTo(6);
        assertThat(report.segments()).extracting(SegmentCount::role).containsExactly(ReaderRole.values());
        assertThat(report.segments()).extracting(SegmentCount::count).containsExactly(1L, 1L, 1L, 1L, 2L);
        assertThat(report.segments().stream().mapToLong(SegmentCount::count).sum()).isEqualTo(report.members());
        assertThat(report.segments().get(4).share()).isEqualTo(2 / 6.0);
        assertThat(report.segments().get(2).label()).isEqualTo("沉默读者");
    }

    @Test
    void segmentsWithoutMembersHaveNoShares() {
        when(repository.members()).thenReturn(List.of());

        SegmentReport report = service.segments(FROM, TO);

        assertThat(report.members()).isZero();
        assertThat(report.segments()).extracting(SegmentCount::share).containsOnlyNulls();
    }

    // ---- 沉默读者 ----

    @Test
    void lurkersUseDefaultsAndPassThroughTheFilter() {
        Lurker lurker = new Lurker(3, "员工3", "部门", FROM, 12, 4, TO);
        when(repository.lurkers(eq(FROM), eq(TO), eq("半导体"), eq(20), eq(0))).thenReturn(new LurkerPage(1, List.of(lurker)));
        when(repository.depts()).thenReturn(List.of("半导体", "光"));

        LurkerReport report = service.lurkers(FROM, TO, " 半导体 ", null, null);

        assertThat(report.total()).isEqualTo(1);
        assertThat(report.limit()).isEqualTo(20);
        assertThat(report.offset()).isZero();
        assertThat(report.lurkers()).containsExactly(lurker);
        assertThat(report.depts()).containsExactly("半导体", "光");
    }

    @Test
    void aBlankDeptMeansNoFilter() {
        when(repository.lurkers(any(), any(), any(), anyInt(), anyInt())).thenReturn(new LurkerPage(0, List.of()));

        service.lurkers(FROM, TO, "  ", 5, 10);

        verify(repository).lurkers(FROM, TO, null, 5, 10);
    }

    @Test
    void lurkersRejectAnOutOfRangeLimitOrOffset() {
        assertThatThrownBy(() -> service.lurkers(FROM, TO, null, 0, 0)).isInstanceOf(InvalidReaderQueryException.class);
        assertThatThrownBy(() -> service.lurkers(FROM, TO, null, 101, 0)).isInstanceOf(InvalidReaderQueryException.class);
        assertThatThrownBy(() -> service.lurkers(FROM, TO, null, 20, -1)).isInstanceOf(InvalidReaderQueryException.class);
    }
}
