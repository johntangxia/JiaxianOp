package com.jiaxian.analytics.leaderboard;

import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.domain.PointsCategory;
import com.jiaxian.analytics.domain.PointsLedger;
import com.jiaxian.analytics.repository.EmployeeRepository;
import com.jiaxian.analytics.repository.PointsLedgerRepository;

import java.time.LocalDateTime;

/** 集成测试夹具：创建员工和积分流水。名字带"榜单测试"前缀，日期统一用 2019 年。 */
class LeaderboardTestFixtures {

    private static final String BATCH = "leaderboard-test";

    final Employee a;
    final Employee b;
    final Employee c;
    final Employee d;
    final Employee e;

    private final PointsLedgerRepository pointsLedgerRepository;

    /** 创建 A~E 五名员工和 本计划"夹具数据"表中的全部流水。 */
    LeaderboardTestFixtures(EmployeeRepository employeeRepository, PointsLedgerRepository pointsLedgerRepository) {
        this.pointsLedgerRepository = pointsLedgerRepository;
        a = employeeRepository.save(Employee.builder().name("榜单测试员工A").simulationBatchId(BATCH).build());
        b = employeeRepository.save(Employee.builder().name("榜单测试员工B").simulationBatchId(BATCH).build());
        c = employeeRepository.save(Employee.builder().name("榜单测试员工C").simulationBatchId(BATCH).build());
        d = employeeRepository.save(Employee.builder().name("榜单测试员工D").simulationBatchId(BATCH).build());
        e = employeeRepository.save(Employee.builder().name("榜单测试员工E").simulationBatchId(BATCH).build());

        ledger(a, PointsCategory.ACTIVE, 1000, LocalDateTime.of(2019, 1, 10, 12, 0));
        ledger(a, PointsCategory.ACTIVE, 100, LocalDateTime.of(2019, 3, 1, 0, 0, 0));
        ledger(a, PointsCategory.PASSIVE, 100, LocalDateTime.of(2019, 3, 15, 9, 0));

        ledger(b, PointsCategory.ACTIVE, 150, LocalDateTime.of(2019, 3, 31, 23, 59, 59));

        ledger(c, PointsCategory.ACTIVE, 20, LocalDateTime.of(2019, 2, 28, 23, 59, 59));
        ledger(c, PointsCategory.PASSIVE, 80, LocalDateTime.of(2019, 3, 10, 9, 0));
        ledger(c, PointsCategory.PASSIVE, 70, LocalDateTime.of(2019, 3, 12, 9, 0));

        ledger(d, PointsCategory.ACTIVE, 40, LocalDateTime.of(2019, 3, 20, 9, 0));

        ledger(e, PointsCategory.ACTIVE, 5, LocalDateTime.of(2019, 4, 1, 0, 0, 0));
    }

    private void ledger(Employee employee, PointsCategory category, int points, LocalDateTime occurTime) {
        pointsLedgerRepository.save(PointsLedger.builder()
            .employeeId(employee.getId()).actionType(category == PointsCategory.ACTIVE ? "POST" : "CONTENT_VIEWED")
            .category(category).points(points).occurTime(occurTime).simulationBatchId(BATCH).build());
    }

    LeaderboardEntry entry(int rank, Employee employee, long points, long active, long passive) {
        return new LeaderboardEntry(rank, employee.getId(), employee.getName(), points, active, passive);
    }
}
