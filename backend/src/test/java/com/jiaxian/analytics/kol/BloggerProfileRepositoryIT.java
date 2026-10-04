package com.jiaxian.analytics.kol;

import com.jiaxian.analytics.domain.ActiveActionType;
import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.domain.PointsCategory;
import com.jiaxian.analytics.repository.ActiveActionLogRepository;
import com.jiaxian.analytics.repository.EmployeeRepository;
import com.jiaxian.analytics.repository.PointsLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class BloggerProfileRepositoryIT {

    @Autowired private BloggerProfileRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ActiveActionLogRepository activeActionLogRepository;
    @Autowired private PointsLedgerRepository pointsLedgerRepository;

    private Employee x;

    private static LocalDateTime t(int month, int day, int hour, int minute) {
        return LocalDateTime.of(2019, month, day, hour, minute);
    }

    private static LocalDate d(int month, int day) {
        return LocalDate.of(2019, month, day);
    }

    @BeforeEach
    void setUp() {
        KolTestFixtures f = new KolTestFixtures(employeeRepository, activeActionLogRepository, pointsLedgerRepository);
        x = f.employee("KOL测试员工X");
        Employee y = f.employee("KOL测试员工Y");

        f.action(x, ActiveActionType.POST, t(6, 3, 9, 0));
        f.action(x, ActiveActionType.COMMENT, t(6, 4, 10, 0));
        f.action(x, ActiveActionType.LOGIN, t(6, 4, 11, 0));
        f.action(x, ActiveActionType.LOGIN, t(6, 4, 12, 0));
        f.action(x, ActiveActionType.LOGIN, t(6, 10, 8, 0));
        f.action(x, ActiveActionType.VIEW, t(6, 5, 9, 0));
        f.action(y, ActiveActionType.LOGIN, t(6, 4, 9, 0));

        f.ledger(x, PointsCategory.ACTIVE, 100, t(6, 3, 12, 0));
        f.ledger(x, PointsCategory.ACTIVE, 20, t(6, 4, 12, 0));
        f.ledger(x, PointsCategory.PASSIVE, 15, t(6, 4, 13, 0));
        f.ledger(x, PointsCategory.PASSIVE, 5, t(6, 11, 12, 0));
        f.ledger(x, PointsCategory.ACTIVE, 777, LocalDateTime.of(2019, 5, 31, 23, 59, 59));
        f.ledger(x, PointsCategory.ACTIVE, 999, LocalDateTime.of(2019, 7, 1, 0, 0, 0));

        insertRfmRow(d(6, 9), 2, "STAR_KOL", 0, 0, 0, 5, 5, 5, 0, false);
        insertRfmRow(d(6, 16), 3, "CHURN_RISK", 0, 0, 0, 2, 5, 5, 0, true);
        insertRfmRow(d(6, 23), 4, "CHURN_RISK", 26, 12, 480, 2, 5, 5, 1234, true);
        jdbc.update("INSERT INTO churn_alert (alert_date, employee_id, metric, recent_daily, baseline_daily, drop_rate)"
                + " VALUES (?, ?, 'ACTIVITY', 0.0, 0.5, 1.0)",
            Timestamp.valueOf(d(6, 23).atStartOfDay()), x.getId());
    }

    private void insertRfmRow(LocalDate asOf, int rank, String segment, int recency, int frequency, long monetary,
                              int r, int f, int m, long rolling, boolean alert) {
        jdbc.update("INSERT INTO blogger_rfm_score (as_of_date, employee_id, rank_no, rolling_points_12m,"
                + " recency_days, frequency, monetary, r_score, f_score, m_score, segment_label, churn_alert)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            Timestamp.valueOf(asOf.atStartOfDay()), x.getId(), rank, rolling, recency, frequency, monetary,
            r, f, m, segment, alert);
    }

    @Test
    void employeeIsFoundByIdOrEmpty() {
        EmployeeInfo info = repository.employee(x.getId()).orElseThrow();

        assertThat(info.id()).isEqualTo(x.getId());
        assertThat(info.name()).isEqualTo("KOL测试员工X");
        assertThat(info.dept()).isNull();
        assertThat(info.joinDate()).isNull();
        assertThat(repository.employee(-1L)).isEmpty();
    }

    @Test
    void dailyActivityCountsOnlyPostCommentAndLoginOfThatEmployee() {
        assertThat(repository.dailyActivity(x.getId(), d(6, 1), d(6, 30))).containsExactlyInAnyOrder(
            new BloggerProfileRepository.DailyActivity(d(6, 3), "POST", 1),
            new BloggerProfileRepository.DailyActivity(d(6, 4), "COMMENT", 1),
            new BloggerProfileRepository.DailyActivity(d(6, 4), "LOGIN", 2),
            new BloggerProfileRepository.DailyActivity(d(6, 10), "LOGIN", 1));
    }

    @Test
    void dailyActivityRangeIsInclusiveOnBothEndDays() {
        assertThat(repository.dailyActivity(x.getId(), d(6, 4), d(6, 4))).containsExactlyInAnyOrder(
            new BloggerProfileRepository.DailyActivity(d(6, 4), "COMMENT", 1),
            new BloggerProfileRepository.DailyActivity(d(6, 4), "LOGIN", 2));
    }

    @Test
    void dailyPointsSplitByCategoryAndExcludeRowsOutsideTheRange() {
        assertThat(repository.dailyPoints(x.getId(), d(6, 1), d(6, 30))).containsExactlyInAnyOrder(
            new BloggerProfileRepository.DailyPoints(d(6, 3), "ACTIVE", 100),
            new BloggerProfileRepository.DailyPoints(d(6, 4), "ACTIVE", 20),
            new BloggerProfileRepository.DailyPoints(d(6, 4), "PASSIVE", 15),
            new BloggerProfileRepository.DailyPoints(d(6, 11), "PASSIVE", 5));
    }

    @Test
    void recentEventsAreNewestFirstAndSkipOtherActionTypes() {
        assertThat(repository.recentEvents(x.getId(), 3)).containsExactly(
            new RecentEvent(t(6, 10, 8, 0), "LOGIN", null),
            new RecentEvent(t(6, 4, 12, 0), "LOGIN", null),
            new RecentEvent(t(6, 4, 11, 0), "LOGIN", null));
        assertThat(repository.recentEvents(x.getId(), 10)).hasSize(5)
            .extracting(RecentEvent::type).doesNotContain("VIEW");
    }

    @Test
    void recentEventsRejectsLimitOutsideSafeRange() {
        // @Repository 的异常转换会把 IllegalArgumentException 包成 InvalidDataAccessApiUsageException
        assertThatThrownBy(() -> repository.recentEvents(x.getId(), 0))
            .isInstanceOf(InvalidDataAccessApiUsageException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> repository.recentEvents(x.getId(), 101))
            .isInstanceOf(InvalidDataAccessApiUsageException.class).hasMessageContaining("limit");
    }

    @Test
    void historyIsAscendingAndCutByTheWindow() {
        assertThat(repository.history(x.getId(), d(6, 1), d(6, 30))).extracting(HistoryPoint::asOf)
            .containsExactly(d(6, 9), d(6, 16), d(6, 23));
        assertThat(repository.history(x.getId(), d(6, 16), d(6, 23))).extracting(HistoryPoint::asOf)
            .containsExactly(d(6, 16), d(6, 23));

        HistoryPoint star = repository.history(x.getId(), d(6, 1), d(6, 9)).get(0);
        assertThat(star).isEqualTo(new HistoryPoint(d(6, 9), 2, Segment.STAR_KOL, "明星KOL", false, 5, 5, 5));
    }

    @Test
    void standingJoinsTheAlertDetailsOfTheSameDay() {
        BloggerProfileRepository.StandingRow row = repository.standing(x.getId(), d(6, 23)).orElseThrow();

        assertThat(row).isEqualTo(new BloggerProfileRepository.StandingRow(4, Segment.CHURN_RISK, true, 1234, 26, 12,
            480, 2, 5, 5, new AlertDetail(ChurnSignal.Metric.ACTIVITY, 0.0, 0.5, 1.0)));
    }

    @Test
    void standingWithoutAnAlertRowHasNoAlertDetailsAndMissingDaysAreEmpty() {
        assertThat(repository.standing(x.getId(), d(6, 16)).orElseThrow().alert()).isNull();
        assertThat(repository.standing(x.getId(), d(6, 30))).isEmpty();
    }
}
