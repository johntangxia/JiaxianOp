package com.jiaxian.analytics.kol;

import com.jiaxian.analytics.pv.Granularity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BloggerProfileServiceTest {

    private static final long ID = 3601;
    private static final EmployeeInfo EMPLOYEE = new EmployeeInfo(ID, "员工-1", null, null);

    private final BloggerProfileRepository repository = mock(BloggerProfileRepository.class);
    private final KolRfmRepository rfmRepository = mock(KolRfmRepository.class);
    private final PlaybookService playbookService = mock(PlaybookService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-10T08:00:00Z"), ZoneOffset.UTC);
    private final BloggerProfileService service =
        new BloggerProfileService(repository, rfmRepository, playbookService, clock);

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    @BeforeEach
    void setUp() {
        when(repository.employee(ID)).thenReturn(Optional.of(EMPLOYEE));
        when(repository.recentEvents(ID, 20)).thenReturn(List.of());
        when(playbookService.lookup()).thenReturn(new PlaybookLookup(List.of(
            new PlaybookEntry(1, Segment.CHURN_RISK, PlaybookAlertStatus.ALERT, "私信关怀"))));
    }

    private static HistoryPoint point(LocalDate asOf, Segment segment) {
        return new HistoryPoint(asOf, 1, segment, segment.displayName(), segment == Segment.CHURN_RISK, 3, 3, 3);
    }

    // ---- profile ----

    @Test
    void profileWithoutAnyRebuildHasNoCurrentAndNoHistory() {
        when(rfmRepository.latestAsOf()).thenReturn(Optional.empty());
        RecentEvent event = new RecentEvent(LocalDateTime.of(2026, 3, 1, 10, 0), "LOGIN", null);
        when(repository.recentEvents(ID, 20)).thenReturn(List.of(event));

        BloggerProfile profile = service.profile(ID, 26);

        assertThat(profile.employee()).isEqualTo(EMPLOYEE);
        assertThat(profile.current()).isNull();
        assertThat(profile.history()).isEmpty();
        assertThat(profile.segmentChanges()).isEmpty();
        assertThat(profile.recentEvents()).containsExactly(event);
        verify(repository, never()).history(anyLong(), any(), any());
    }

    @Test
    void profileBuildsCurrentHistoryAndSegmentChanges() {
        LocalDate latest = d(2026, 3, 1);
        when(rfmRepository.latestAsOf()).thenReturn(Optional.of(latest));
        // weeks=3 → 最近 3 次每周重算：窗口 [latest − 14 天, latest]
        when(repository.history(ID, d(2026, 2, 15), latest)).thenReturn(List.of(
            point(d(2026, 2, 15), Segment.STAR_KOL), point(d(2026, 2, 22), Segment.STAR_KOL),
            point(latest, Segment.CHURN_RISK)));
        when(repository.standing(ID, latest)).thenReturn(Optional.of(new BloggerProfileRepository.StandingRow(
            7, Segment.CHURN_RISK, true, 5200, 26, 12, 480, 2, 5, 5,
            new AlertDetail(ChurnSignal.Metric.ACTIVITY, 0.0, 0.52, 1.0))));

        BloggerProfile profile = service.profile(ID, 3);

        CurrentStanding current = profile.current();
        assertThat(current.asOf()).isEqualTo(latest);
        assertThat(current.rank()).isEqualTo(7);
        assertThat(current.segment()).isEqualTo(Segment.CHURN_RISK);
        assertThat(current.segmentName()).isEqualTo("流失预警");
        assertThat(current.churnAlert()).isTrue();
        assertThat(current.recommendation()).isEqualTo("私信关怀");
        assertThat(current.rollingPoints12m()).isEqualTo(5200);
        assertThat(current.recencyDays()).isEqualTo(26);
        assertThat(current.alert()).isEqualTo(new AlertDetail(ChurnSignal.Metric.ACTIVITY, 0.0, 0.52, 1.0));
        assertThat(profile.history()).hasSize(3);
        assertThat(profile.segmentChanges()).containsExactly(
            new SegmentChange(latest, Segment.STAR_KOL, Segment.CHURN_RISK));
    }

    @Test
    void anEmployeeNotInTheLatestRunHasNoCurrentButKeepsTheHistory() {
        LocalDate latest = d(2026, 3, 1);
        when(rfmRepository.latestAsOf()).thenReturn(Optional.of(latest));
        when(repository.standing(ID, latest)).thenReturn(Optional.empty());
        when(repository.history(ID, d(2025, 9, 7), latest)).thenReturn(List.of(point(d(2025, 12, 7), Segment.STEADY)));

        BloggerProfile profile = service.profile(ID, 26);

        assertThat(profile.current()).isNull();
        assertThat(profile.history()).hasSize(1);
    }

    @Test
    void profileRejectsBadWeeksAndUnknownEmployees() {
        assertThatThrownBy(() -> service.profile(ID, 0))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("weeks");
        assertThatThrownBy(() -> service.profile(ID, 105))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("weeks");

        when(repository.employee(-1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.profile(-1L, 26)).isInstanceOf(EmployeeNotFoundException.class);
    }

    // ---- activity ----

    @Test
    void activityBucketsDailyRowsIntoWeeks() {
        // from=06-03 扩展到周一 06-01；查库范围是 [06-01, 06-09]
        when(repository.dailyActivity(ID, d(2026, 6, 1), d(2026, 6, 9))).thenReturn(List.of(
            new BloggerProfileRepository.DailyActivity(d(2026, 6, 2), "POST", 2),
            new BloggerProfileRepository.DailyActivity(d(2026, 6, 3), "LOGIN", 3),
            new BloggerProfileRepository.DailyActivity(d(2026, 6, 4), "COMMENT", 1),
            new BloggerProfileRepository.DailyActivity(d(2026, 6, 9), "LOGIN", 4)));

        ActivitySeries series = service.activity(ID, Granularity.WEEK, d(2026, 6, 3), d(2026, 6, 9));

        assertThat(series.granularity()).isEqualTo(Granularity.WEEK);
        assertThat(series.from()).isEqualTo(d(2026, 6, 3));
        assertThat(series.to()).isEqualTo(d(2026, 6, 9));
        assertThat(series.points()).containsExactly(
            new ActivityPoint(d(2026, 6, 1), 2, 1, 3, true),
            new ActivityPoint(d(2026, 6, 8), 0, 0, 4, false));
    }

    @Test
    void activityDefaultsToTwentySixWeeksEndingToday() {
        when(repository.dailyActivity(ID, d(2025, 12, 15), d(2026, 6, 10))).thenReturn(List.of());

        ActivitySeries series = service.activity(ID, null, null, null);

        assertThat(series.granularity()).isEqualTo(Granularity.WEEK);
        assertThat(series.from()).isEqualTo(d(2025, 12, 17));
        assertThat(series.to()).isEqualTo(d(2026, 6, 10));
        assertThat(series.points()).hasSize(26);
        assertThat(series.points()).allSatisfy(p -> {
            assertThat(p.posts()).isZero();
            assertThat(p.logins()).isZero();
        });
        assertThat(series.points().get(25).complete()).isFalse();
    }

    @Test
    void dailyActivityDefaultsToThirtyDays() {
        when(repository.dailyActivity(ID, d(2026, 5, 12), d(2026, 6, 10))).thenReturn(List.of());

        ActivitySeries series = service.activity(ID, Granularity.DAY, null, null);

        assertThat(series.from()).isEqualTo(d(2026, 5, 12));
        assertThat(series.points()).hasSize(30);
    }

    @Test
    void aRangeInTheFutureHasNoPointsAndDoesNotQuery() {
        ActivitySeries series = service.activity(ID, Granularity.MONTH, d(2026, 7, 1), d(2026, 7, 31));

        assertThat(series.points()).isEmpty();
        verify(repository, never()).dailyActivity(anyLong(), any(), any());
    }

    @Test
    void activityRejectsBadRanges() {
        assertThatThrownBy(() -> service.activity(ID, Granularity.DAY, d(2026, 6, 5), d(2026, 6, 1)))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("from");
        assertThatThrownBy(() -> service.activity(ID, Granularity.DAY, d(2025, 1, 1), d(2026, 6, 10)))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("400");

        when(repository.employee(-1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.activity(-1L, Granularity.WEEK, null, null))
            .isInstanceOf(EmployeeNotFoundException.class);
    }

    // ---- points-trend ----

    @Test
    void pointsTrendSplitsActiveAndPassivePerPeriod() {
        when(repository.dailyPoints(ID, d(2026, 6, 1), d(2026, 6, 9))).thenReturn(List.of(
            new BloggerProfileRepository.DailyPoints(d(2026, 6, 2), "ACTIVE", 100),
            new BloggerProfileRepository.DailyPoints(d(2026, 6, 3), "PASSIVE", 15),
            new BloggerProfileRepository.DailyPoints(d(2026, 6, 9), "ACTIVE", 20)));

        PointsTrend trend = service.pointsTrend(ID, Granularity.WEEK, d(2026, 6, 3), d(2026, 6, 9));

        assertThat(trend.points()).containsExactly(
            new PointsTrendPoint(d(2026, 6, 1), 100, 15, 115, true),
            new PointsTrendPoint(d(2026, 6, 8), 20, 0, 20, false));
    }

    @Test
    void pointsTrendDefaultsToTwelveMonthsForMonthGranularity() {
        // to=06-10，from = 往前 11 个月 = 2025-07-10 → 扩展到 2025-07-01
        when(repository.dailyPoints(ID, d(2025, 7, 1), d(2026, 6, 10))).thenReturn(List.of());

        PointsTrend trend = service.pointsTrend(ID, Granularity.MONTH, null, null);

        assertThat(trend.points()).hasSize(12);
        assertThat(trend.points().get(0).periodStart()).isEqualTo(d(2025, 7, 1));
        assertThat(trend.points().get(11).complete()).isFalse();
    }
}
