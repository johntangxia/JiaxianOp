package com.jiaxian.analytics.pv;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PvSeriesBuilderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 10);

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    @Test
    void weekBucketsExpandFromToPeriodStartAndSumDays() {
        // from=06-03(周三) 向前扩展到周一 06-01，所以 06-02 的 5 也算进去
        List<DailyPv> daily = List.of(
            new DailyPv(d(2026, 6, 2), 5), new DailyPv(d(2026, 6, 3), 7), new DailyPv(d(2026, 6, 9), 4));

        List<PvBucket> buckets = PvSeriesBuilder.build(daily, Granularity.WEEK, d(2026, 6, 3), d(2026, 6, 9), TODAY);

        assertThat(buckets).containsExactly(
            new PvBucket(d(2026, 6, 1), 12, true),    // 06-01 ~ 06-07，已结束
            new PvBucket(d(2026, 6, 8), 4, false));   // 06-08 ~ 06-14，今天(06-10)还在这一周里
    }

    @Test
    void emptyPeriodsAreFilledWithZero() {
        List<DailyPv> daily = List.of(new DailyPv(d(2026, 1, 10), 3), new DailyPv(d(2026, 3, 5), 8));

        List<PvBucket> buckets = PvSeriesBuilder.build(daily, Granularity.MONTH, d(2026, 1, 15), d(2026, 3, 20), TODAY);

        assertThat(buckets).containsExactly(
            new PvBucket(d(2026, 1, 1), 3, true),
            new PvBucket(d(2026, 2, 1), 0, true),
            new PvBucket(d(2026, 3, 1), 8, true));
    }

    @Test
    void toBeyondTodayIsClampedAndTodayIsIncomplete() {
        List<DailyPv> daily = List.of(
            new DailyPv(d(2026, 6, 7), 100),   // 早于 from，必须被忽略
            new DailyPv(d(2026, 6, 9), 2),
            new DailyPv(d(2026, 6, 10), 6));

        List<PvBucket> buckets = PvSeriesBuilder.build(daily, Granularity.DAY, d(2026, 6, 8), d(2026, 6, 30), TODAY);

        assertThat(buckets).containsExactly(
            new PvBucket(d(2026, 6, 8), 0, true),
            new PvBucket(d(2026, 6, 9), 2, true),
            new PvBucket(d(2026, 6, 10), 6, false));
    }

    @Test
    void currentYearIsIncomplete() {
        List<DailyPv> daily = List.of(new DailyPv(d(2025, 3, 1), 10), new DailyPv(d(2026, 2, 1), 4));

        List<PvBucket> buckets = PvSeriesBuilder.build(daily, Granularity.YEAR, d(2025, 6, 1), d(2026, 6, 1), TODAY);

        assertThat(buckets).containsExactly(
            new PvBucket(d(2025, 1, 1), 10, true),
            new PvBucket(d(2026, 1, 1), 4, false));
    }

    @Test
    void fromAfterTodayYieldsNoBuckets() {
        List<PvBucket> buckets = PvSeriesBuilder.build(List.of(), Granularity.DAY, d(2026, 7, 1), d(2026, 7, 31), TODAY);

        assertThat(buckets).isEmpty();
    }
}
