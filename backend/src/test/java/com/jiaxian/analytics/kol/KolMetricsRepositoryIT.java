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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class KolMetricsRepositoryIT {

    private static final LocalDate AS_OF = LocalDate.of(2019, 6, 30);

    @Autowired private KolMetricsRepository repository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ActiveActionLogRepository activeActionLogRepository;
    @Autowired private PointsLedgerRepository pointsLedgerRepository;

    private KolTestFixtures f;
    private Employee e1;
    private Employee e2;
    private Employee e3;

    private static LocalDateTime t(int month, int day, int hour, int minute, int second) {
        return LocalDateTime.of(2019, month, day, hour, minute, second);
    }

    @BeforeEach
    void setUp() {
        f = new KolTestFixtures(employeeRepository, activeActionLogRepository, pointsLedgerRepository);
        e1 = f.employee("KOL测试员工E1");
        e2 = f.employee("KOL测试员工E2");
        e3 = f.employee("KOL测试员工E3");
        Employee e4 = f.employee("KOL测试员工E4");

        // E1：各个窗口边界
        f.action(e1, ActiveActionType.LOGIN, t(6, 30, 12, 0, 0));
        f.action(e1, ActiveActionType.LOGIN, t(6, 1, 0, 0, 0));       // 近期起点（含）
        f.action(e1, ActiveActionType.POST, t(5, 31, 23, 59, 59));    // 基线，不是近期
        f.action(e1, ActiveActionType.COMMENT, t(4, 2, 0, 0, 0));     // 近 90 天起点（含）
        f.action(e1, ActiveActionType.LOGIN, t(4, 1, 23, 59, 59));    // 早于近 90 天，仍在基线内
        f.action(e1, ActiveActionType.VIEW, t(6, 29, 12, 0, 0));      // 不是发帖/评论/登录，不计
        f.action(e1, ActiveActionType.LOGIN, LocalDateTime.of(2019, 7, 1, 0, 0, 0));   // 统计日之后，不计
        f.ledger(e1, PointsCategory.ACTIVE, 20, t(6, 30, 12, 0, 0));
        f.ledger(e1, PointsCategory.ACTIVE, 100, t(6, 1, 0, 0, 0));
        f.ledger(e1, PointsCategory.ACTIVE, 50, t(5, 31, 23, 59, 59));
        f.ledger(e1, PointsCategory.PASSIVE, 30, t(5, 2, 0, 0, 0));    // 上一个近期起点（含）
        f.ledger(e1, PointsCategory.PASSIVE, 7, t(5, 1, 23, 59, 59));
        f.ledger(e1, PointsCategory.ACTIVE, 11, t(4, 2, 0, 0, 0));
        f.ledger(e1, PointsCategory.ACTIVE, 5, t(4, 1, 23, 59, 59));   // 只在基线内
        f.ledger(e1, PointsCategory.ACTIVE, 1000, LocalDateTime.of(2018, 7, 1, 0, 0, 0));    // 12 个月起点（含）
        f.ledger(e1, PointsCategory.ACTIVE, 9999, LocalDateTime.of(2018, 6, 30, 23, 59, 59)); // 超出 12 个月
        f.ledger(e1, PointsCategory.ACTIVE, 777, LocalDateTime.of(2019, 7, 1, 0, 0, 0));      // 统计日之后

        // E2：中等
        f.ledger(e2, PointsCategory.ACTIVE, 300, t(6, 15, 12, 0, 0));
        f.action(e2, ActiveActionType.POST, t(5, 20, 12, 0, 0));
        f.action(e2, ActiveActionType.LOGIN, t(6, 10, 12, 0, 0));
        f.action(e2, ActiveActionType.VIEW, t(6, 29, 12, 0, 0));

        // E3：只有很久以前的少量被动积分，没有任何活动
        f.ledger(e3, PointsCategory.PASSIVE, 5, t(2, 1, 12, 0, 0));

        // E4：12 个月内没有任何积分 → 不在名单里
        f.ledger(e4, PointsCategory.ACTIVE, 500, LocalDateTime.of(2018, 6, 1, 12, 0, 0));
    }

    @Test
    void populationIsRankedByTwelveMonthPointsWithAllWindowsComputed() {
        List<BloggerMetrics> population = repository.loadPopulation(AS_OF, new KolProperties());

        assertThat(population).containsExactly(
            // 12 个月 = 20+100+50+30+7+11+5+1000；近 90 天 M = 20+100+50+30+7+11；F = 4（06-30、06-01、05-31、04-02）
            // 近期：活跃 2、积分 120；基线：活跃 3（05-31、04-02、04-01）、积分 50+30+7+11+5=103；上一个近期：50+30=80
            new BloggerMetrics(e1.getId(), 1, 1223, 0, 4, 218, 2, 3, 120, 103, 80),
            // E2：最近一次登录 06-10 → 距 06-30 有 20 天；近期 1（06-10）、基线 1（05-20）
            new BloggerMetrics(e2.getId(), 2, 300, 20, 2, 300, 1, 1, 300, 0, 0),
            // E3：没有活动记录 → recencyDays 为 null
            new BloggerMetrics(e3.getId(), 3, 5, null, 0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void topNCutsTheList() {
        KolProperties properties = new KolProperties();
        properties.setTopN(2);

        assertThat(repository.loadPopulation(AS_OF, properties))
            .extracting(BloggerMetrics::employeeId).containsExactly(e1.getId(), e2.getId());
    }

    @Test
    void windowLengthsComeFromTheProperties() {
        // 近 30 天窗口：起点 2019-06-01 → E1 的 F = 06-30、06-01 两次，M = 20+100
        KolProperties properties = new KolProperties();
        properties.setWindowDays(30);

        BloggerMetrics first = repository.loadPopulation(AS_OF, properties).get(0);

        assertThat(first.frequency()).isEqualTo(2);
        assertThat(first.monetary()).isEqualTo(120);
    }

    @Test
    void emptyPopulationWhenThereIsNoPointsInTheTwelveMonths() {
        assertThat(repository.loadPopulation(LocalDate.of(2015, 1, 1), new KolProperties())).isEmpty();
    }
}
