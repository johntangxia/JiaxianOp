package com.jiaxian.analytics.kol;

import com.jiaxian.analytics.pv.Granularity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeriodAxisTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 10);   // 周三

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    @Test
    void weekPeriodsExpandFromToTheMondayAndMarkTheCurrentWeekIncomplete() {
        // from=06-03(周三) 向前扩展到周一 06-01；06-08 这一周今天(06-10)还在里面
        List<PeriodAxis.Period> periods =
            PeriodAxis.periods(Granularity.WEEK, d(2026, 6, 3), d(2026, 6, 9), TODAY, 400);

        assertThat(periods).containsExactly(
            new PeriodAxis.Period(d(2026, 6, 1), true),
            new PeriodAxis.Period(d(2026, 6, 8), false));
    }

    @Test
    void toBeyondTodayIsClamped() {
        List<PeriodAxis.Period> periods =
            PeriodAxis.periods(Granularity.DAY, d(2026, 6, 8), d(2026, 6, 30), TODAY, 400);

        assertThat(periods).containsExactly(
            new PeriodAxis.Period(d(2026, 6, 8), true),
            new PeriodAxis.Period(d(2026, 6, 9), true),
            new PeriodAxis.Period(d(2026, 6, 10), false));
    }

    @Test
    void monthPeriodsAreContinuous() {
        List<PeriodAxis.Period> periods =
            PeriodAxis.periods(Granularity.MONTH, d(2026, 1, 15), d(2026, 3, 20), TODAY, 400);

        assertThat(periods).containsExactly(
            new PeriodAxis.Period(d(2026, 1, 1), true),
            new PeriodAxis.Period(d(2026, 2, 1), true),
            new PeriodAxis.Period(d(2026, 3, 1), true));
    }

    @Test
    void aRangeEntirelyInTheFutureHasNoPeriods() {
        assertThat(PeriodAxis.periods(Granularity.MONTH, d(2026, 7, 1), d(2026, 7, 31), TODAY, 400)).isEmpty();
    }

    @Test
    void theNumberOfPeriodsIsCapped() {
        // 2026-05-12 ~ 2026-06-10 共 30 天
        assertThat(PeriodAxis.periods(Granularity.DAY, d(2026, 5, 12), d(2026, 6, 10), TODAY, 30)).hasSize(30);
        assertThatThrownBy(() -> PeriodAxis.periods(Granularity.DAY, d(2026, 5, 12), d(2026, 6, 10), TODAY, 29))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("29");
        // 巨大范围不会先生成再检查：遇到上限就停
        assertThatThrownBy(() -> PeriodAxis.periods(Granularity.DAY, d(1000, 1, 1), d(2026, 6, 10), TODAY, 400))
            .isInstanceOf(InvalidKolQueryException.class);
    }
}
