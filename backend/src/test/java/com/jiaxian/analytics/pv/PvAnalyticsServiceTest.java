package com.jiaxian.analytics.pv;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PvAnalyticsServiceTest {

    private static final LocalDate JUNE_10 = LocalDate.of(2026, 6, 10);

    private final PvDailyQueryRepository repository = mock(PvDailyQueryRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-10T08:00:00Z"), ZoneOffset.UTC);
    private final PvAnalyticsService service = new PvAnalyticsService(repository, clock);

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    @Test
    void trendFetchesOneExtraYearAndReturnsOnlyRequestedPeriods() {
        // 请求 2026-03；起点 2026-03-01 往前 1 年 1 周 = 2025-02-22
        when(repository.dailyPv(d(2025, 2, 22), d(2026, 3, 31), PvFilter.NONE)).thenReturn(List.of(
            new DailyPv(d(2025, 3, 10), 100), new DailyPv(d(2026, 2, 15), 50), new DailyPv(d(2026, 3, 10), 150)));

        PvTrend trend = service.trend(Granularity.MONTH, d(2026, 3, 1), d(2026, 3, 31), PvFilter.NONE, 1);

        assertThat(trend.granularity()).isEqualTo(Granularity.MONTH);
        assertThat(trend.from()).isEqualTo(d(2026, 3, 1));
        assertThat(trend.to()).isEqualTo(d(2026, 3, 31));
        assertThat(trend.points()).hasSize(1);
        PvPoint march = trend.points().get(0);
        assertThat(march.periodStart()).isEqualTo(d(2026, 3, 1));
        assertThat(march.pv()).isEqualTo(150);
        assertThat(march.momRate()).isCloseTo(2.0, within(1e-9));   // (150-50)/50
        assertThat(march.yoyRate()).isCloseTo(0.5, within(1e-9));   // (150-100)/100（去年同期来自多取的那一年）
        assertThat(march.complete()).isTrue();

        // 预测基于全部已完成周期：最近 3 个月 0, 50, 150 → ma = 200/3；前 3 个月 0 → slope = ma/3
        assertThat(trend.forecast()).hasSize(1);
        assertThat(trend.forecast().get(0).periodStart()).isEqualTo(d(2026, 4, 1));
        assertThat(trend.forecast().get(0).value()).isCloseTo(800.0 / 9, within(1e-9));
    }

    @Test
    void trendWithToBeyondTodayOnlyQueriesUntilToday() {
        // 起点 2026-06-10 往前 1 年 1 周 = 2025-06-03；to 超过今天，只查到今天
        when(repository.dailyPv(d(2025, 6, 3), JUNE_10, PvFilter.NONE)).thenReturn(List.of(new DailyPv(JUNE_10, 6)));

        PvTrend trend = service.trend(Granularity.DAY, d(2026, 6, 10), d(2026, 12, 31), PvFilter.NONE, 0);

        assertThat(trend.points()).hasSize(1);
        assertThat(trend.points().get(0).pv()).isEqualTo(6);
        assertThat(trend.points().get(0).complete()).isFalse();
        assertThat(trend.forecast()).isEmpty();
    }

    @Test
    void trendForAFutureRangeHasNoPoints() {
        PvTrend trend = service.trend(Granularity.MONTH, d(2026, 7, 1), d(2026, 7, 31), PvFilter.NONE, 1);

        assertThat(trend.points()).isEmpty();
    }

    @Test
    void trendRejectsBadArguments() {
        assertThatThrownBy(() -> service.trend(Granularity.DAY, d(2026, 6, 5), d(2026, 6, 1), PvFilter.NONE, 1))
            .isInstanceOf(InvalidPvQueryException.class).hasMessageContaining("from");
        assertThatThrownBy(() -> service.trend(Granularity.DAY, d(2026, 6, 1), d(2026, 6, 5), PvFilter.NONE, -1))
            .isInstanceOf(InvalidPvQueryException.class).hasMessageContaining("forecastPeriods");
        assertThatThrownBy(() -> service.trend(Granularity.DAY, d(2026, 6, 1), d(2026, 6, 5), PvFilter.NONE, 13))
            .isInstanceOf(InvalidPvQueryException.class).hasMessageContaining("forecastPeriods");
    }

    @Test
    void topDelegatesToRepository() {
        List<PvTopEntry> rows = List.of(new PvTopEntry(7L, "半导体", 3456));
        when(repository.top(PvDimension.CHANNEL, d(2026, 1, 1), d(2026, 3, 31), PvFilter.NONE, 5)).thenReturn(rows);

        assertThat(service.top(PvDimension.CHANNEL, d(2026, 1, 1), d(2026, 3, 31), PvFilter.NONE, 5)).isEqualTo(rows);
        verify(repository).top(PvDimension.CHANNEL, d(2026, 1, 1), d(2026, 3, 31), PvFilter.NONE, 5);
    }

    @Test
    void topRejectsBadArguments() {
        assertThatThrownBy(() -> service.top(PvDimension.CHANNEL, d(2026, 6, 5), d(2026, 6, 1), PvFilter.NONE, 10))
            .isInstanceOf(InvalidPvQueryException.class).hasMessageContaining("from");
        assertThatThrownBy(() -> service.top(PvDimension.CHANNEL, d(2026, 6, 1), d(2026, 6, 5), PvFilter.NONE, 0))
            .isInstanceOf(InvalidPvQueryException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> service.top(PvDimension.CHANNEL, d(2026, 6, 1), d(2026, 6, 5), PvFilter.NONE, 101))
            .isInstanceOf(InvalidPvQueryException.class).hasMessageContaining("limit");
    }
}
