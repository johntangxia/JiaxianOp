package com.jiaxian.analytics.leaderboard;

import com.jiaxian.analytics.repository.EmployeeRepository;
import com.jiaxian.analytics.repository.PointsLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class LeaderboardQueryRepositoryIT {

    @Autowired private LeaderboardQueryRepository repository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private PointsLedgerRepository pointsLedgerRepository;

    private LeaderboardTestFixtures f;

    @BeforeEach
    void setUp() {
        f = new LeaderboardTestFixtures(employeeRepository, pointsLedgerRepository);
    }

    private List<LeaderboardEntry> live(PeriodType type, String period, int limit) {
        LocalDate start = type.parse(period);
        return repository.live(start, type.nextStart(start), limit);
    }

    @Test
    void marchBoardRanksByInPeriodPointsWithTiesAndBoundaries() {
        // A 200(100/100)；B 150(150/0)；C 150(0/150)；D 40；E 的 04-01 零点和 C 的 02-28 23:59:59 都不算 3 月
        assertThat(live(PeriodType.MONTH, "2019-03", 50)).containsExactly(
            f.entry(1, f.a, 200, 100, 100),
            f.entry(2, f.b, 150, 150, 0),
            f.entry(2, f.c, 150, 0, 150),
            f.entry(4, f.d, 40, 40, 0));
    }

    @Test
    void limitCutsTheList() {
        assertThat(live(PeriodType.MONTH, "2019-03", 2)).containsExactly(
            f.entry(1, f.a, 200, 100, 100),
            f.entry(2, f.b, 150, 150, 0));
    }

    @Test
    void onlyInPeriodPointsCountNotCumulative() {
        // A 在 1 月有 1000 分，2 月没有任何流水，所以不在 2 月榜上；2 月只有 C 的 02-28 23:59:59
        assertThat(live(PeriodType.MONTH, "2019-02", 50)).containsExactly(f.entry(1, f.c, 20, 20, 0));
        assertThat(live(PeriodType.MONTH, "2019-01", 50)).containsExactly(f.entry(1, f.a, 1000, 1000, 0));
    }

    @Test
    void periodStartIsInclusive() {
        assertThat(live(PeriodType.MONTH, "2019-04", 50)).containsExactly(f.entry(1, f.e, 5, 5, 0));
    }

    @Test
    void yearBoardSumsTheWholeYear() {
        assertThat(live(PeriodType.YEAR, "2019", 50)).containsExactly(
            f.entry(1, f.a, 1200, 1100, 100),
            f.entry(2, f.c, 170, 20, 150),
            f.entry(3, f.b, 150, 150, 0),
            f.entry(4, f.d, 40, 40, 0),
            f.entry(5, f.e, 5, 5, 0));
    }

    @Test
    void periodWithoutLedgerRowsIsEmpty() {
        assertThat(live(PeriodType.YEAR, "2018", 50)).isEmpty();
    }

    @Test
    void periodsWithLedgerListsMonthsAndYearsThatHaveRows() {
        assertThat(repository.periodsWithLedger(PeriodType.MONTH)).contains(
            LocalDate.of(2019, 1, 1), LocalDate.of(2019, 2, 1), LocalDate.of(2019, 3, 1), LocalDate.of(2019, 4, 1));
        assertThat(repository.periodsWithLedger(PeriodType.MONTH)).doesNotContain(LocalDate.of(2019, 5, 1));
        assertThat(repository.periodsWithLedger(PeriodType.YEAR)).contains(LocalDate.of(2019, 1, 1));
        assertThat(repository.periodsWithLedger(PeriodType.YEAR)).doesNotContain(LocalDate.of(2018, 1, 1));
    }

    @Test
    void snapshotDoesNotExistUntilItIsBuilt() {
        assertThat(repository.snapshotExists(PeriodType.MONTH, "2019-03")).isFalse();
        assertThat(repository.snapshot(PeriodType.MONTH, "2019-03", 50)).isEmpty();
    }

    @Test
    void liveRejectsLimitOutsideSafeRange() {
        // @Repository 的异常转换会把 IllegalArgumentException 包成 InvalidDataAccessApiUsageException
        assertThatThrownBy(() -> live(PeriodType.MONTH, "2019-03", 0))
            .isInstanceOf(InvalidDataAccessApiUsageException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> live(PeriodType.MONTH, "2019-03", 101))
            .isInstanceOf(InvalidDataAccessApiUsageException.class).hasMessageContaining("limit");
    }
}
