package com.jiaxian.analytics.leaderboard;

import com.jiaxian.analytics.repository.EmployeeRepository;
import com.jiaxian.analytics.repository.PointsLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class LeaderboardSnapshotServiceIT {

    @Autowired private LeaderboardSnapshotService snapshotService;
    @Autowired private LeaderboardQueryRepository repository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private PointsLedgerRepository pointsLedgerRepository;
    @Autowired private JdbcTemplate jdbc;

    private LeaderboardTestFixtures f;

    @BeforeEach
    void setUp() {
        f = new LeaderboardTestFixtures(employeeRepository, pointsLedgerRepository);
    }

    @Test
    void rebuildWritesRankedRowsThatMatchTheLiveBoard() {
        int written = snapshotService.rebuild(PeriodType.MONTH, "2019-03");

        assertThat(written).isEqualTo(4);
        assertThat(repository.snapshotExists(PeriodType.MONTH, "2019-03")).isTrue();
        assertThat(repository.snapshot(PeriodType.MONTH, "2019-03", 50)).containsExactly(
            f.entry(1, f.a, 200, 100, 100),
            f.entry(2, f.b, 150, 150, 0),
            f.entry(2, f.c, 150, 0, 150),
            f.entry(4, f.d, 40, 40, 0));
        assertThat(repository.snapshot(PeriodType.MONTH, "2019-03", 50))
            .isEqualTo(repository.live(LocalDate.of(2019, 3, 1), LocalDate.of(2019, 4, 1), 50));
    }

    @Test
    void yearSnapshotUsesTheWholeYear() {
        assertThat(snapshotService.rebuild(PeriodType.YEAR, "2019")).isEqualTo(5);
        assertThat(repository.snapshot(PeriodType.YEAR, "2019", 50)).containsExactly(
            f.entry(1, f.a, 1200, 1100, 100),
            f.entry(2, f.c, 170, 20, 150),
            f.entry(3, f.b, 150, 150, 0),
            f.entry(4, f.d, 40, 40, 0),
            f.entry(5, f.e, 5, 5, 0));
    }

    @Test
    void rebuildIsIdempotent() {
        snapshotService.rebuild(PeriodType.MONTH, "2019-03");
        int writtenAgain = snapshotService.rebuild(PeriodType.MONTH, "2019-03");

        assertThat(writtenAgain).isEqualTo(4);
        assertThat(repository.snapshot(PeriodType.MONTH, "2019-03", 50)).hasSize(4);
    }

    @Test
    void rebuildReplacesTheOldSnapshot() {
        snapshotService.rebuild(PeriodType.MONTH, "2019-03");
        jdbc.update("DELETE FROM points_ledger WHERE employee_id = ? AND occur_time >= ? AND occur_time < ?",
            f.a.getId(), java.sql.Timestamp.valueOf("2019-03-01 00:00:00"), java.sql.Timestamp.valueOf("2019-04-01 00:00:00"));

        snapshotService.rebuild(PeriodType.MONTH, "2019-03");

        // A 的 3 月流水没了：B、C 并列第 1，D 第 3
        assertThat(repository.snapshot(PeriodType.MONTH, "2019-03", 50)).containsExactly(
            f.entry(1, f.b, 150, 150, 0),
            f.entry(1, f.c, 150, 0, 150),
            f.entry(3, f.d, 40, 40, 0));
    }

    @Test
    void rebuildRejectsPeriodsThatHaveNotEnded() {
        LocalDate today = LocalDate.now();
        String thisMonth = PeriodType.MONTH.format(PeriodType.MONTH.containing(today));
        String thisYear = PeriodType.YEAR.format(PeriodType.YEAR.containing(today));
        String nextYear = PeriodType.YEAR.format(PeriodType.YEAR.containing(today).plusYears(1));

        assertThatThrownBy(() -> snapshotService.rebuild(PeriodType.MONTH, thisMonth))
            .isInstanceOf(InvalidLeaderboardQueryException.class).hasMessageContaining("not ended");
        assertThatThrownBy(() -> snapshotService.rebuild(PeriodType.YEAR, thisYear))
            .isInstanceOf(InvalidLeaderboardQueryException.class);
        assertThatThrownBy(() -> snapshotService.rebuild(PeriodType.YEAR, nextYear))
            .isInstanceOf(InvalidLeaderboardQueryException.class);
    }

    @Test
    void rebuildRejectsMalformedPeriod() {
        assertThatThrownBy(() -> snapshotService.rebuild(PeriodType.MONTH, "2019-3"))
            .isInstanceOf(InvalidLeaderboardQueryException.class);
    }

    @Test
    void rebuildAllEndedCoversEveryEndedPeriodWithLedgerRowsButNotTheCurrentOne() {
        SnapshotSummary summary = snapshotService.rebuildAllEnded();

        assertThat(summary.months()).isGreaterThanOrEqualTo(4);   // 至少 2019-01..04
        assertThat(summary.years()).isGreaterThanOrEqualTo(1);    // 至少 2019
        assertThat(summary.rowsWritten()).isGreaterThanOrEqualTo(1 + 1 + 4 + 1 + 5);   // 2019 年 1~4 月各榜 + 年榜
        assertThat(repository.snapshotExists(PeriodType.MONTH, "2019-03")).isTrue();
        assertThat(repository.snapshotExists(PeriodType.YEAR, "2019")).isTrue();

        LocalDate today = LocalDate.now();
        assertThat(repository.snapshotExists(PeriodType.MONTH,
            PeriodType.MONTH.format(PeriodType.MONTH.containing(today)))).isFalse();
        assertThat(repository.snapshotExists(PeriodType.YEAR,
            PeriodType.YEAR.format(PeriodType.YEAR.containing(today)))).isFalse();
    }

    @Test
    void snapshotRowsAreRemovedWhenTheEmployeeIsDeleted() {
        snapshotService.rebuild(PeriodType.MONTH, "2019-03");
        jdbc.update("DELETE FROM points_ledger WHERE employee_id = ?", f.d.getId());
        jdbc.update("DELETE FROM employee WHERE id = ?", f.d.getId());

        Long rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM leaderboard_snapshot WHERE employee_id = ?", Long.class, f.d.getId());
        assertThat(rows).isZero();
    }
}
