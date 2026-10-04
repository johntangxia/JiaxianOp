package com.jiaxian.analytics.simulator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class HistoricalBackfillPersonaIT {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 3, 1);
    private static final int EMPLOYEES = 20;
    private static final long SEED = 5;

    @Autowired private HistoricalBackfillService backfillService;
    @Autowired private JdbcTemplate jdbc;

    private SimulationConfig config(boolean personas) {
        return SimulationConfig.builder()
            .batchId("persona-it").employeeCount(EMPLOYEES).accountCount(8).teamAccountRatio(0.2).contentCount(20)
            .historyStart(START).historyEnd(END).randomSeed(SEED).personas(personas).build();
    }

    private long loginCount(long employeeId, LocalDate from, LocalDate toExclusive) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM active_action_log WHERE employee_id = ?"
                + " AND action_type = 'LOGIN' AND occur_time >= ? AND occur_time < ?", Long.class,
            employeeId, Timestamp.valueOf(from.atStartOfDay()), Timestamp.valueOf(toExclusive.atStartOfDay()));
    }

    @Test
    void personasShapeEachEmployeesLoginPattern() {
        backfillService.runBackfill(config(true));

        List<Long> employeeIds = jdbc.queryForList("SELECT id FROM employee ORDER BY id", Long.class);
        List<BloggerPersona> personas = PersonaAssigner.assign(EMPLOYEES, SEED);
        assertThat(employeeIds).hasSize(EMPLOYEES);

        for (int i = 0; i < EMPLOYEES; i++) {
            long id = employeeIds.get(i);
            switch (personas.get(i)) {
                case STAR -> assertThat(loginCount(id, END.minusDays(60), END)).as("STAR #%d", i).isGreaterThan(30);
                case CHURNING -> {
                    assertThat(loginCount(id, END.minusDays(60), END.plusDays(1))).as("CHURNING #%d", i).isZero();
                    assertThat(loginCount(id, START, END.minusDays(60))).as("CHURNING #%d", i).isGreaterThan(100);
                }
                case RISING -> assertThat(loginCount(id, START, END.plusDays(1))).as("RISING #%d", i).isEqualTo(2);
                case DORMANT -> {
                    assertThat(loginCount(id, START, END.plusDays(1))).as("DORMANT #%d", i).isEqualTo(8);
                    assertThat(loginCount(id, END.minusDays(120), END.plusDays(1))).as("DORMANT #%d", i).isZero();
                }
                case RANDOM -> assertThat(loginCount(id, START, END.plusDays(1))).as("RANDOM #%d", i).isBetween(5L, 24L);
            }
        }
    }

    @Test
    void withoutPersonasEveryoneKeepsTheOriginalRandomLogins() {
        backfillService.runBackfill(config(false));

        List<Long> employeeIds = jdbc.queryForList("SELECT id FROM employee ORDER BY id", Long.class);
        for (long id : employeeIds) {
            assertThat(loginCount(id, START, END.plusDays(1))).isBetween(5L, 24L);
        }
    }
}
