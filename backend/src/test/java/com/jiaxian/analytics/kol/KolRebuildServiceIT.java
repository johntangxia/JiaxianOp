package com.jiaxian.analytics.kol;

import com.jiaxian.analytics.repository.ActiveActionLogRepository;
import com.jiaxian.analytics.repository.EmployeeRepository;
import com.jiaxian.analytics.repository.PointsLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
@Transactional
class KolRebuildServiceIT {

    private static final LocalDate AS_OF = LocalDate.of(2019, 6, 30);

    private record Row(long employeeId, int rank, long rolling, Integer recency, int frequency, long monetary,
                       int rScore, int fScore, int mScore, String segment, boolean alert) {
    }

    @Autowired private KolRebuildService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ActiveActionLogRepository activeActionLogRepository;
    @Autowired private PointsLedgerRepository pointsLedgerRepository;

    private KolTestFixtures.ChurnScenario s;

    @BeforeEach
    void setUp() {
        s = new KolTestFixtures(employeeRepository, activeActionLogRepository, pointsLedgerRepository)
            .churnScenario();
    }

    private List<Row> rows(LocalDate asOf) {
        return jdbc.query("SELECT employee_id, rank_no, rolling_points_12m, recency_days, frequency, monetary,"
                + " r_score, f_score, m_score, segment_label, churn_alert"
                + " FROM blogger_rfm_score WHERE as_of_date = ? ORDER BY rank_no",
            (rs, n) -> new Row(rs.getLong(1), rs.getInt(2), rs.getLong(3), rs.getObject(4, Integer.class),
                rs.getInt(5), rs.getLong(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getString(10),
                rs.getBoolean(11)),
            Timestamp.valueOf(asOf.atStartOfDay()));
    }

    private void insertPreviousRow(LocalDate asOf, long employeeId, String segment) {
        jdbc.update("INSERT INTO blogger_rfm_score (as_of_date, employee_id, rank_no, rolling_points_12m,"
                + " recency_days, frequency, monetary, r_score, f_score, m_score, segment_label, churn_alert)"
                + " VALUES (?, ?, 1, 0, 0, 0, 0, 5, 5, 5, ?, false)",
            Timestamp.valueOf(asOf.atStartOfDay()), employeeId, segment);
    }

    @Test
    void rebuildWritesScoresSegmentsAndAlerts() {
        KolRebuildResult result = service.rebuild(AS_OF);

        assertThat(result).isEqualTo(new KolRebuildResult(AS_OF, 3, 1));
        assertThat(rows(AS_OF)).containsExactly(
            new Row(s.q().getId(), 1, 600, 0, 30, 600, 2, 4, 4, "STEADY", false),
            new Row(s.r().getId(), 2, 300, 0, 15, 300, 2, 2, 2, "DORMANT", false),
            new Row(s.p().getId(), 3, 10, 81, 9, 0, 1, 1, 1, "DORMANT", true));

        List<Object[]> alerts = jdbc.query(
            "SELECT employee_id, metric, recent_daily, baseline_daily, drop_rate FROM churn_alert WHERE alert_date = ?",
            (rs, n) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5)},
            Timestamp.valueOf(AS_OF.atStartOfDay()));
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0)[0]).isEqualTo(s.p().getId());
        assertThat(alerts.get(0)[1]).isEqualTo("ACTIVITY");
        assertThat((double) alerts.get(0)[2]).isCloseTo(0.0, within(1e-9));
        assertThat((double) alerts.get(0)[3]).isCloseTo(0.1, within(1e-9));
        assertThat((double) alerts.get(0)[4]).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void previousStarMakesTheAlertedPersonChurnRisk() {
        insertPreviousRow(AS_OF.minusDays(7), s.p().getId(), "STAR_KOL");

        service.rebuild(AS_OF);

        assertThat(rows(AS_OF)).extracting(Row::segment).containsExactly("STEADY", "DORMANT", "CHURN_RISK");
    }

    @Test
    void aPreviousPeriodThatWasNotStarOrSteadyDoesNotMakeChurnRisk() {
        insertPreviousRow(AS_OF.minusDays(7), s.p().getId(), "OBSERVE");

        service.rebuild(AS_OF);

        assertThat(rows(AS_OF)).extracting(Row::segment).containsExactly("STEADY", "DORMANT", "DORMANT");
    }

    @Test
    void rebuildIsIdempotent() {
        service.rebuild(AS_OF);
        KolRebuildResult again = service.rebuild(AS_OF);

        assertThat(again).isEqualTo(new KolRebuildResult(AS_OF, 3, 1));
        assertThat(rows(AS_OF)).hasSize(3);
        Long alertRows = jdbc.queryForObject("SELECT COUNT(*) FROM churn_alert WHERE alert_date = ?", Long.class,
            Timestamp.valueOf(AS_OF.atStartOfDay()));
        assertThat(alertRows).isEqualTo(1);
    }

    @Test
    void rebuildWeeksRunsOldestFirstAndKeepsAllWeeks() {
        List<KolRebuildResult> results = service.rebuildWeeks(AS_OF, 2);

        assertThat(results).extracting(KolRebuildResult::asOf)
            .containsExactly(AS_OF.minusDays(7), AS_OF);
        assertThat(rows(AS_OF.minusDays(7))).hasSize(3);
        assertThat(rows(AS_OF)).hasSize(3);
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThatThrownBy(() -> service.rebuild(LocalDate.now().plusDays(1)))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("future");
        assertThatThrownBy(() -> service.rebuildWeeks(AS_OF, 0))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("weeks");
        assertThatThrownBy(() -> service.rebuildWeeks(AS_OF, 53))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("weeks");
    }

    @Test
    void emptyPopulationWritesNothing() {
        assertThat(service.rebuild(LocalDate.of(2015, 1, 1))).isEqualTo(new KolRebuildResult(LocalDate.of(2015, 1, 1), 0, 0));
    }

    @Test
    void resultsAreRemovedWhenTheEmployeeIsDeleted() {
        service.rebuild(AS_OF);
        jdbc.update("DELETE FROM active_action_log WHERE employee_id = ?", s.p().getId());
        jdbc.update("DELETE FROM points_ledger WHERE employee_id = ?", s.p().getId());
        jdbc.update("DELETE FROM employee WHERE id = ?", s.p().getId());

        Long scoreRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM blogger_rfm_score WHERE employee_id = ?", Long.class, s.p().getId());
        Long alertRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM churn_alert WHERE employee_id = ?", Long.class, s.p().getId());
        assertThat(scoreRows).isZero();
        assertThat(alertRows).isZero();
    }
}
