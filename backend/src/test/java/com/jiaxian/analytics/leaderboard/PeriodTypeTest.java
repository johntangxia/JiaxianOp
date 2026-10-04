package com.jiaxian.analytics.leaderboard;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeriodTypeTest {

    @Test
    void parsesValidPeriods() {
        assertThat(PeriodType.MONTH.parse("2026-09")).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(PeriodType.YEAR.parse("2026")).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void rejectsMalformedPeriods() {
        for (String bad : new String[] {"2026-9", "2026-13", "abc", "", "2026-09-01", "202609"}) {
            assertThatThrownBy(() -> PeriodType.MONTH.parse(bad))
                .as("MONTH %s", bad).isInstanceOf(InvalidLeaderboardQueryException.class);
        }
        for (String bad : new String[] {"26", "2026-01", "abcd", "", "20266"}) {
            assertThatThrownBy(() -> PeriodType.YEAR.parse(bad))
                .as("YEAR %s", bad).isInstanceOf(InvalidLeaderboardQueryException.class);
        }
    }

    @Test
    void formatsPeriodStarts() {
        assertThat(PeriodType.MONTH.format(LocalDate.of(2026, 9, 1))).isEqualTo("2026-09");
        assertThat(PeriodType.YEAR.format(LocalDate.of(2026, 1, 1))).isEqualTo("2026");
    }

    @Test
    void findsThePeriodContainingADate() {
        assertThat(PeriodType.MONTH.containing(LocalDate.of(2026, 9, 17))).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(PeriodType.YEAR.containing(LocalDate.of(2026, 9, 17))).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void nextStartCrossesMonthAndYearBoundaries() {
        assertThat(PeriodType.MONTH.nextStart(LocalDate.of(2026, 2, 1))).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(PeriodType.MONTH.nextStart(LocalDate.of(2026, 12, 1))).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(PeriodType.YEAR.nextStart(LocalDate.of(2026, 1, 1))).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void resolvesLowercasePathVariables() {
        assertThat(PeriodType.fromPath("month")).isEqualTo(PeriodType.MONTH);
        assertThat(PeriodType.fromPath("year")).isEqualTo(PeriodType.YEAR);
        assertThatThrownBy(() -> PeriodType.fromPath("week")).isInstanceOf(InvalidLeaderboardQueryException.class);
        assertThatThrownBy(() -> PeriodType.fromPath("MONTH")).isInstanceOf(InvalidLeaderboardQueryException.class);
    }
}
