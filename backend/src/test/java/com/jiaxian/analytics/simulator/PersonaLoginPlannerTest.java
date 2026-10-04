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

        assertThat(logins.size()).isBetween(297, 381);   // 424 天 × 0.8 ± 约 5σ
        assertThat(logins).allSatisfy(t -> {
            assertThat(t.toLocalDate()).isAfterOrEqualTo(START).isBefore(END);
        });
        // 最后 60 天仍然活跃（和流失者的区别）
        assertThat(logins).anyMatch(t -> !t.toLocalDate().isBefore(END.minusDays(60)));
    }

    @Test
    void churningStopsLoggingInSixtyDaysBeforeTheEnd() {
        List<LocalDateTime> logins = plan(BloggerPersona.CHURNING);

        assertThat(logins.size()).isBetween(250, 330);   // 364 天 × 0.8
        assertThat(logins).allSatisfy(t -> assertThat(t.toLocalDate()).isBefore(END.minusDays(60)));
    }

    @Test
    void risingLogsInExactlyTwiceTwentyAndFourDaysBeforeTheEnd() {
        List<LocalDateTime> logins = plan(BloggerPersona.RISING);

        assertThat(logins).containsExactlyInAnyOrder(
            END.minusDays(20).atTime(10, 0), END.minusDays(4).atTime(10, 0));
    }

    @Test
    void risingSkipsLoginsThatWouldFallBeforeTheHistoryStart() {
        List<LocalDateTime> logins = PersonaLoginPlanner.plan(
            BloggerPersona.RISING, END.minusDays(10), END, new Random(7));

        assertThat(logins).containsExactly(END.minusDays(4).atTime(10, 0));
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
