package com.jiaxian.analytics.simulator;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersonaLoginPlannerTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 3, 1);   // 共 424 天

    private static List<LocalDateTime> plan(BloggerPersona persona) {
        return PersonaLoginPlanner.plan(persona, START, END, new Random(7));
    }

    @Test
    void starLogsInAboutEightyPercentOfDaysAcrossTheWholeHistory() {
        List<LocalDateTime> logins = plan(BloggerPersona.STAR);

        assertThat(logins.size()).isBetween(235, 320);   // 424 天 × 0.8 × 周末因子折算 0.81 ≈ 276
        assertThat(logins).allSatisfy(t -> {
            assertThat(t.toLocalDate()).isAfterOrEqualTo(START).isBefore(END);
        });
        // 最后 60 天仍然活跃（和流失者的区别）
        assertThat(logins).anyMatch(t -> !t.toLocalDate().isBefore(END.minusDays(60)));
    }

    @Test
    void churningStopsLoggingInSixtyDaysBeforeTheEnd() {
        List<LocalDateTime> logins = plan(BloggerPersona.CHURNING);

        assertThat(logins.size()).isBetween(195, 280);   // 364 天 × 0.8 × 0.81 ≈ 237
        assertThat(logins).allSatisfy(t -> assertThat(t.toLocalDate()).isBefore(END.minusDays(60)));
    }

    @Test
    void risingLogsInFromTheJoinDateWithAtLeastTwoLoginsAndTheLastOneNearTheEnd() {
        LocalDate join = END.minusDays(40);
        for (long seed = 1; seed <= 20; seed++) {
            List<LocalDateTime> logins = PersonaLoginPlanner.plan(BloggerPersona.RISING, START, END, join, new Random(seed));

            assertThat(logins.size()).as("seed %d", seed).isGreaterThanOrEqualTo(2);
            assertThat(logins).allSatisfy(t -> assertThat(t.toLocalDate()).isAfterOrEqualTo(join).isBefore(END));
            LocalDate last = logins.stream().map(LocalDateTime::toLocalDate).max(LocalDate::compareTo).orElseThrow();
            assertThat(last).isBetween(END.minusDays(3), END.minusDays(1));
        }
    }

    @Test
    void risingLastLoginsAreSpreadOverSeveralDaysInsteadOfPilingUpOnTheLastDay() {
        java.util.Set<LocalDate> lastDays = new java.util.HashSet<>();
        for (long seed = 1; seed <= 30; seed++) {
            List<LocalDateTime> logins = PersonaLoginPlanner.plan(BloggerPersona.RISING, START, END, END.minusDays(40), new Random(seed));
            lastDays.add(logins.stream().map(LocalDateTime::toLocalDate).max(LocalDate::compareTo).orElseThrow());
        }

        assertThat(lastDays).hasSize(3);
    }

    @Test
    void risingLogsInAboutTwelvePercentOfDaysSinceJoining() {
        long total = 0;
        for (long seed = 1; seed <= 50; seed++) {
            total += PersonaLoginPlanner.plan(BloggerPersona.RISING, START, END, END.minusDays(50), new Random(seed)).size();
        }

        assertThat(total / 50.0).isBetween(3.0, 8.0);   // 约 47 天 × 0.12 × 0.81 ≈ 4.6，加上保证的最近一次登录
    }

    @Test
    void risingBeforeTheHistoryStartStillStartsAtTheHistoryStart() {
        List<LocalDateTime> logins = PersonaLoginPlanner.plan(BloggerPersona.RISING, END.minusDays(10), END,
            END.minusDays(500), new Random(7));

        assertThat(logins).isNotEmpty().allSatisfy(t -> assertThat(t.toLocalDate()).isAfterOrEqualTo(END.minusDays(10)));
    }

    @Test
    void starAndChurningLogInLessOnWeekends() {
        List<LocalDateTime> logins = plan(BloggerPersona.STAR);
        long weekend = logins.stream().filter(t -> t.getDayOfWeek().getValue() >= 6).count();
        long weekday = logins.size() - weekend;

        // 每个周末日的登录率只有工作日的 35%：周末日均 / 工作日均 ≈ 0.35
        double ratio = (weekend / (424 * 2.0 / 7)) / (weekday / (424 * 5.0 / 7));
        assertThat(ratio).isBetween(0.2, 0.5);
    }

    @Test
    void dormantLogsInEightTimesAllBeforeOneHundredTwentyDaysFromTheEnd() {
        List<LocalDateTime> logins = plan(BloggerPersona.DORMANT);

        assertThat(logins).hasSize(8);
        assertThat(logins).allSatisfy(t -> {
            assertThat(t.toLocalDate()).isAfterOrEqualTo(START).isBefore(END.minusDays(120));
        });
    }

    @Test
    void randomPersonaIsNotPlannedHere() {
        assertThatThrownBy(() -> plan(BloggerPersona.RANDOM)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSameSeedGivesTheSamePlan() {
        assertThat(plan(BloggerPersona.STAR)).isEqualTo(plan(BloggerPersona.STAR));
    }
}
