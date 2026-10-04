package com.jiaxian.analytics.pv;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class GranularityTest {

    @Test
    void weekStartsOnMonday() {
        // 2026-03-04 是周三，2026-03-02 是周一，2026-03-08 是周日
        assertThat(Granularity.WEEK.periodStart(LocalDate.of(2026, 3, 4))).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(Granularity.WEEK.periodStart(LocalDate.of(2026, 3, 2))).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(Granularity.WEEK.periodStart(LocalDate.of(2026, 3, 8))).isEqualTo(LocalDate.of(2026, 3, 2));
    }

    @Test
    void dayMonthAndYearStarts() {
        LocalDate date = LocalDate.of(2026, 3, 4);
        assertThat(Granularity.DAY.periodStart(date)).isEqualTo(date);
        assertThat(Granularity.MONTH.periodStart(date)).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(Granularity.YEAR.periodStart(date)).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void periodEnds() {
        assertThat(Granularity.DAY.periodEnd(LocalDate.of(2026, 3, 4))).isEqualTo(LocalDate.of(2026, 3, 4));
        assertThat(Granularity.WEEK.periodEnd(LocalDate.of(2026, 3, 2))).isEqualTo(LocalDate.of(2026, 3, 8));
        assertThat(Granularity.MONTH.periodEnd(LocalDate.of(2026, 2, 1))).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(Granularity.MONTH.periodEnd(LocalDate.of(2024, 2, 1))).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(Granularity.YEAR.periodEnd(LocalDate.of(2026, 1, 1))).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void nextPeriodStart() {
        assertThat(Granularity.DAY.nextPeriodStart(LocalDate.of(2026, 3, 31))).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(Granularity.WEEK.nextPeriodStart(LocalDate.of(2026, 3, 2))).isEqualTo(LocalDate.of(2026, 3, 9));
        assertThat(Granularity.MONTH.nextPeriodStart(LocalDate.of(2026, 12, 1))).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(Granularity.YEAR.nextPeriodStart(LocalDate.of(2026, 1, 1))).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void sameLastYear() {
        // 2 月 29 日回到去年 2 月 28 日
        assertThat(Granularity.DAY.sameLastYear(LocalDate.of(2024, 2, 29))).isEqualTo(LocalDate.of(2023, 2, 28));
        // 52 周 = 364 天：2026-03-02 往前 364 天是 2025-03-03（同样是周一）
        assertThat(Granularity.WEEK.sameLastYear(LocalDate.of(2026, 3, 2))).isEqualTo(LocalDate.of(2025, 3, 3));
        assertThat(Granularity.MONTH.sameLastYear(LocalDate.of(2026, 3, 1))).isEqualTo(LocalDate.of(2025, 3, 1));
        assertThat(Granularity.YEAR.sameLastYear(LocalDate.of(2026, 1, 1))).isEqualTo(LocalDate.of(2025, 1, 1));
    }

    @Test
    void forecastWindows() {
        assertThat(Granularity.DAY.forecastWindow()).isEqualTo(7);
        assertThat(Granularity.WEEK.forecastWindow()).isEqualTo(4);
        assertThat(Granularity.MONTH.forecastWindow()).isEqualTo(3);
        assertThat(Granularity.YEAR.forecastWindow()).isEqualTo(2);
    }
}
