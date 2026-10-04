package com.jiaxian.analytics.kol;

import com.jiaxian.analytics.domain.ActiveActionLog;
import com.jiaxian.analytics.domain.ActiveActionType;
import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.domain.PointsCategory;
import com.jiaxian.analytics.domain.PointsLedger;
import com.jiaxian.analytics.repository.ActiveActionLogRepository;
import com.jiaxian.analytics.repository.EmployeeRepository;
import com.jiaxian.analytics.repository.PointsLedgerRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 集成测试夹具：员工、活跃行为、积分流水。名字带"KOL测试"前缀，日期统一用 2019 年。 */
class KolTestFixtures {

    private static final String BATCH = "kol-test";

    private final EmployeeRepository employeeRepository;
    private final ActiveActionLogRepository activeActionLogRepository;
    private final PointsLedgerRepository pointsLedgerRepository;

    KolTestFixtures(EmployeeRepository employeeRepository, ActiveActionLogRepository activeActionLogRepository,
                    PointsLedgerRepository pointsLedgerRepository) {
        this.employeeRepository = employeeRepository;
        this.activeActionLogRepository = activeActionLogRepository;
        this.pointsLedgerRepository = pointsLedgerRepository;
    }

    Employee employee(String name) {
        return employeeRepository.save(Employee.builder().name(name).simulationBatchId(BATCH).build());
    }

    void action(Employee employee, ActiveActionType type, LocalDateTime occurTime) {
        activeActionLogRepository.save(ActiveActionLog.builder()
            .employeeId(employee.getId()).actionType(type).occurTime(occurTime).simulationBatchId(BATCH).build());
    }

    void ledger(Employee employee, PointsCategory category, int points, LocalDateTime occurTime) {
        pointsLedgerRepository.save(PointsLedger.builder()
            .employeeId(employee.getId()).actionType(category == PointsCategory.ACTIVE ? "POST" : "CONTENT_VIEWED")
            .category(category).points(points).occurTime(occurTime).simulationBatchId(BATCH).build());
    }

    /** 从 from 到 toInclusive 每天 12:00 一次登录。 */
    void loginsOnDays(Employee employee, LocalDate from, LocalDate toInclusive) {
        for (LocalDate day = from; !day.isAfter(toInclusive); day = day.plusDays(1)) {
            action(employee, ActiveActionType.LOGIN, day.atTime(12, 0));
        }
    }

    record ChurnScenario(Employee p, Employee q, Employee r) {
    }

    /**
     * 统计日 2019-06-30 的三人场景（Task 5/6 共用，手算结果见各测试）：
     * <ul>
     *   <li>P：2019-04-02..04-10 每天登录（9 次），2019-03-10 有 10 分被动积分 → 之前活跃、之后沉默，触发预警；</li>
     *   <li>Q：2019-06-01..06-30 每天登录（30 次），06-15 有 600 分主动积分；</li>
     *   <li>R：2019-06-16..06-30 每天登录（15 次），06-20 有 300 分主动积分。</li>
     * </ul>
     */
    ChurnScenario churnScenario() {
        Employee p = employee("KOL测试员工P");
        Employee q = employee("KOL测试员工Q");
        Employee r = employee("KOL测试员工R");

        loginsOnDays(p, LocalDate.of(2019, 4, 2), LocalDate.of(2019, 4, 10));
        ledger(p, PointsCategory.PASSIVE, 10, LocalDateTime.of(2019, 3, 10, 12, 0));

        loginsOnDays(q, LocalDate.of(2019, 6, 1), LocalDate.of(2019, 6, 30));
        ledger(q, PointsCategory.ACTIVE, 600, LocalDateTime.of(2019, 6, 15, 12, 0));

        loginsOnDays(r, LocalDate.of(2019, 6, 16), LocalDate.of(2019, 6, 30));
        ledger(r, PointsCategory.ACTIVE, 300, LocalDateTime.of(2019, 6, 20, 12, 0));

        return new ChurnScenario(p, q, r);
    }
}
