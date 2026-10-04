# 积分排行榜（月榜/年榜）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付积分排行榜后端：快照表（定时 + 手动重算）、月榜/年榜查询接口（进行中实时算，已结束读快照，缺快照回退实时算）。

**Architecture:** 榜单分数 = 周期内 `points_ledger` 区间求和。实时榜和快照榜共用同一条 `RANK() OVER` 逻辑，保证名次一致。快照表 `leaderboard_snapshot` 用 `JdbcTemplate` 读写、无 JPA 实体，随员工级联删除。`PeriodType` 是纯逻辑；`LeaderboardService` 持有 `Clock`（复用 Plan 3 `PvConfig` 的 Bean）决定数据来源。

**Tech Stack:** Spring Boot 3.2.5 (Java 17), JdbcTemplate, OpenGauss 5.0.0（A 兼容模式）, JUnit 5 + Mockito + AssertJ, Spring `@Scheduled`。

**设计文档：** `docs/superpowers/specs/2026-10-04-leaderboard-design.md`（先读它）。

---

## 必读：约定与踩坑记录

沿用 Plan 3（`docs/superpowers/plans/2026-10-04-pv-analytics.md`）的"约定与踩坑记录"，与本计划直接相关的：

1. **日期参数一律绑定 `java.sql.Timestamp`**（`Timestamp.valueOf(date.atStartOfDay())`）。`points_ledger.occur_time` 是真正的 `TIMESTAMP`；`period_value` 是字符串，不涉及 A 兼容模式的 `DATE` 问题。需要按月/年取周期起点时用 `date_trunc('month'|'year', occur_time)`，**不要** `CAST(… AS DATE)`。
2. **`LIMIT` 必须是校验过的整数字面量**，不能绑定参数。`SELECT ?, ?, …` 这种"裸参数放在选择列表里"的写法类型无法推断，快照插入语句里的 `period_type` / `period_value` 用校验过的字面量拼接（枚举名 + 规范化后的周期字符串）。
3. **`@Repository` 会把 `IllegalArgumentException` 包成 `InvalidDataAccessApiUsageException`**（Plan 3 踩过）。参数校验放在 Service 里抛自己的异常，Repository 里的越界检查只是兜底。
4. **`@Transactional` 自调用不生效**：`rebuildAllEnded()` 内部调用 `rebuild()` 时，事务靠 `rebuildAllEnded()` 自己的 `@Transactional` 提供，所以它必须自己加注解。
5. **集成测试 `*IT` 连真实 OpenGauss**；测试优先 `@Transactional`（自动回滚）；夹具日期统一用 **2019 年**（库里没有这个年份的真实数据，断言可以精确），夹具名字用"榜单测试"前缀。
6. **每个任务完成后都跑完整 `cd backend && mvn test`**；本计划不改 `pom.xml`，不需要联网。
7. 端口 8080 被无关进程占用——手工启动应用时用 `--server.port=8093`，用完杀掉（可能要 `pkill -f spring-boot:run` 再确认 `lsof -i :8093` 为空）。
8. `HistoricalBackfillService.runBackfill()` 会 `deleteAll()` 全部员工，所以快照表对员工的外键必须 `ON DELETE CASCADE`。

## 文件结构

新建（`backend/src/main/java/com/jiaxian/analytics/leaderboard/`，测试在对应的 `src/test/java/…/leaderboard/` 下，包名 `com.jiaxian.analytics.leaderboard`）：

| 文件 | 职责 |
|---|---|
| `PeriodType.java` | 纯逻辑：`MONTH`/`YEAR` 的起点、下一周期起点、格式化、解析、路径变量解析 |
| `LeaderboardEntry.java`、`Leaderboard.java`、`SnapshotSummary.java`、`SnapshotRebuildResult.java` | 记录类 |
| `InvalidLeaderboardQueryException.java` | 参数非法 → 400 |
| `LeaderboardQueryRepository.java` | 实时榜、快照榜、快照是否存在、有流水的周期 |
| `LeaderboardSnapshotService.java` | 重算快照 |
| `LeaderboardSnapshotScheduler.java` | 定时重算上一个月/上一年 |
| `LeaderboardService.java` | 校验参数、选数据来源 |
| `LeaderboardController.java`、`LeaderboardAdminController.java`、`LeaderboardExceptionHandler.java` | HTTP 接口与 400 处理 |

修改：`backend/src/main/resources/schema.sql`（追加表）、`backend/src/test/resources/application.properties`（关定时任务）、`docs/superpowers/specs/2026-09-23-community-analytics-design.md`（同步两处差异）。

测试：`PeriodTypeTest`、`LeaderboardTestFixtures`、`LeaderboardQueryRepositoryIT`、`LeaderboardSnapshotServiceIT`、`LeaderboardSnapshotSchedulerTest`、`LeaderboardServiceTest`、`LeaderboardControllerIT`、`LeaderboardEndToEndIT`。

## 夹具数据（2019 年，Task 2/3 共用，手算结果见各测试）

员工 A、B、C、D、E（按此顺序创建，id 递增）：

| 员工 | 流水（时间 / 类别 / 分） |
|---|---|
| A | 2019-01-10 ACTIVE 1000；2019-03-01 00:00:00 ACTIVE 100；2019-03-15 PASSIVE 100 |
| B | 2019-03-31 23:59:59 ACTIVE 150 |
| C | 2019-02-28 23:59:59 ACTIVE 20；2019-03-10 PASSIVE 80；2019-03-12 PASSIVE 70 |
| D | 2019-03-20 ACTIVE 40 |
| E | 2019-04-01 00:00:00 ACTIVE 5 |

手算：
- **3 月**：A 200（主动100/被动100）名次1；B 150（150/0）名次2；C 150（0/150）名次2；D 40（40/0）名次4。并列的 B、C 按员工 id 升序，B 在前。E 的 04-01 零点不算 3 月，C 的 02-28 23:59:59 也不算。
- **2 月**：只有 C 20（主动 20）。A 1 月分很高，但 2 月没有流水，不在 2 月榜上。
- **1 月**：只有 A 1000。**4 月**：只有 E 5（月初零点算本月）。
- **2019 年**：A 1200（主动1100/被动100）1；C 170（20/150）2；B 150 3；D 40 4；E 5（5/0）5。
- **2018 年**：空。

---

### Task 1: 周期类型、记录类与异常

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/leaderboard/PeriodType.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/leaderboard/InvalidLeaderboardQueryException.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/leaderboard/LeaderboardEntry.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/leaderboard/Leaderboard.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/leaderboard/SnapshotSummary.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/leaderboard/SnapshotRebuildResult.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/leaderboard/PeriodTypeTest.java`

- [ ] **Step 1: 写测试**

`backend/src/test/java/com/jiaxian/analytics/leaderboard/PeriodTypeTest.java`：

```java
package com.jiaxian.analytics.leaderboard;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeriodTypeTest {

    @Test
    void parsesValidPeriods() {
        assertThat(PeriodType.MONTH.parse("2026-09")).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(PeriodType.YEAR.parse("2026")).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void rejectsMalformedPeriods() {
        for (String bad : new String[] {"2026-9", "2026-13", "abc", "", "2026-09-01", "202609"}) {
            assertThatThrownBy(() -> PeriodType.MONTH.parse(bad))
                .as("MONTH %s", bad).isInstanceOf(InvalidLeaderboardQueryException.class);
        }
        for (String bad : new String[] {"26", "2026-01", "abcd", "", "20266"}) {
            assertThatThrownBy(() -> PeriodType.YEAR.parse(bad))
                .as("YEAR %s", bad).isInstanceOf(InvalidLeaderboardQueryException.class);
        }
    }

    @Test
    void formatsPeriodStarts() {
        assertThat(PeriodType.MONTH.format(LocalDate.of(2026, 9, 1))).isEqualTo("2026-09");
        assertThat(PeriodType.YEAR.format(LocalDate.of(2026, 1, 1))).isEqualTo("2026");
    }

    @Test
    void findsThePeriodContainingADate() {
        assertThat(PeriodType.MONTH.containing(LocalDate.of(2026, 9, 17))).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(PeriodType.YEAR.containing(LocalDate.of(2026, 9, 17))).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void nextStartCrossesMonthAndYearBoundaries() {
        assertThat(PeriodType.MONTH.nextStart(LocalDate.of(2026, 2, 1))).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(PeriodType.MONTH.nextStart(LocalDate.of(2026, 12, 1))).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(PeriodType.YEAR.nextStart(LocalDate.of(2026, 1, 1))).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void resolvesLowercasePathVariables() {
        assertThat(PeriodType.fromPath("month")).isEqualTo(PeriodType.MONTH);
        assertThat(PeriodType.fromPath("year")).isEqualTo(PeriodType.YEAR);
        assertThatThrownBy(() -> PeriodType.fromPath("week")).isInstanceOf(InvalidLeaderboardQueryException.class);
        assertThatThrownBy(() -> PeriodType.fromPath("MONTH")).isInstanceOf(InvalidLeaderboardQueryException.class);
    }
}
```

- [ ] **Step 2: 写实现**

`backend/src/main/java/com/jiaxian/analytics/leaderboard/InvalidLeaderboardQueryException.java`：

```java
package com.jiaxian.analytics.leaderboard;

/** 请求参数非法，对应 HTTP 400。 */
public class InvalidLeaderboardQueryException extends RuntimeException {

    public InvalidLeaderboardQueryException(String message) {
        super(message);
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/leaderboard/PeriodType.java`：

```java
package com.jiaxian.analytics.leaderboard;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/** 榜单周期：月（yyyy-MM）或年（yyyy）。区间是左闭右开 [起点, 下一周期起点)。 */
public enum PeriodType {
    MONTH, YEAR;

    private static final Pattern MONTH_FORMAT = Pattern.compile("\\d{4}-\\d{2}");
    private static final Pattern YEAR_FORMAT = Pattern.compile("\\d{4}");

    /** 把 "2026-09" / "2026" 解析成周期起点。 */
    public LocalDate parse(String value) {
        try {
            return switch (this) {
                case MONTH -> {
                    if (!MONTH_FORMAT.matcher(value).matches()) {
                        throw new DateTimeParseException("bad month", value, 0);
                    }
                    yield YearMonth.parse(value).atDay(1);
                }
                case YEAR -> {
                    if (!YEAR_FORMAT.matcher(value).matches()) {
                        throw new DateTimeParseException("bad year", value, 0);
                    }
                    yield LocalDate.of(Integer.parseInt(value), 1, 1);
                }
            };
        } catch (DateTimeParseException e) {
            throw new InvalidLeaderboardQueryException(
                "period must look like " + (this == MONTH ? "yyyy-MM" : "yyyy"));
        }
    }

    /** 周期起点 → "2026-09" / "2026"。 */
    public String format(LocalDate periodStart) {
        return switch (this) {
            case MONTH -> String.format("%04d-%02d", periodStart.getYear(), periodStart.getMonthValue());
            case YEAR -> String.format("%04d", periodStart.getYear());
        };
    }

    /** date 所在周期的起点。 */
    public LocalDate containing(LocalDate date) {
        return switch (this) {
            case MONTH -> date.withDayOfMonth(1);
            case YEAR -> date.withDayOfYear(1);
        };
    }

    public LocalDate nextStart(LocalDate periodStart) {
        return switch (this) {
            case MONTH -> periodStart.plusMonths(1);
            case YEAR -> periodStart.plusYears(1);
        };
    }

    /** 路径变量只接受小写 month / year。 */
    public static PeriodType fromPath(String path) {
        return switch (path) {
            case "month" -> MONTH;
            case "year" -> YEAR;
            default -> throw new InvalidLeaderboardQueryException("leaderboard type must be 'month' or 'year'");
        };
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/leaderboard/LeaderboardEntry.java`：

```java
package com.jiaxian.analytics.leaderboard;

/** 榜单的一行。并列的员工名次相同（1,2,2,4）。 */
public record LeaderboardEntry(int rank, long employeeId, String name,
                               long points, long activePoints, long passivePoints) {
}
```

`backend/src/main/java/com/jiaxian/analytics/leaderboard/Leaderboard.java`：

```java
package com.jiaxian.analytics.leaderboard;

import java.util.List;

public record Leaderboard(PeriodType periodType, String periodValue, Status status, Source source,
                          List<LeaderboardEntry> entries) {

    /** IN_PROGRESS：周期还没结束；ARCHIVED：已结束。 */
    public enum Status { IN_PROGRESS, ARCHIVED }

    /** LIVE：实时对积分流水求和；SNAPSHOT：读已归档的快照。 */
    public enum Source { LIVE, SNAPSHOT }
}
```

`backend/src/main/java/com/jiaxian/analytics/leaderboard/SnapshotSummary.java`：

```java
package com.jiaxian.analytics.leaderboard;

/** rebuildAllEnded 的结果：重算了多少个月、多少个年，共写入多少行。 */
public record SnapshotSummary(int months, int years, int rowsWritten) {
}
```

`backend/src/main/java/com/jiaxian/analytics/leaderboard/SnapshotRebuildResult.java`：

```java
package com.jiaxian.analytics.leaderboard;

public record SnapshotRebuildResult(PeriodType periodType, String periodValue, int rowsWritten) {
}
```

- [ ] **Step 3: 运行测试**

Run: `cd backend && mvn test -Dtest=PeriodTypeTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 4: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src/main/java/com/jiaxian/analytics/leaderboard backend/src/test/java/com/jiaxian/analytics/leaderboard
git commit -m "feat: add leaderboard period type and records

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 快照表与查询仓库

**Files:**
- Modify: `backend/src/main/resources/schema.sql`（末尾追加）
- Create: `backend/src/main/java/com/jiaxian/analytics/leaderboard/LeaderboardQueryRepository.java`
- Create: `backend/src/test/java/com/jiaxian/analytics/leaderboard/LeaderboardTestFixtures.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/leaderboard/LeaderboardQueryRepositoryIT.java`

- [ ] **Step 1: 追加表结构**

在 `backend/src/main/resources/schema.sql` 文件末尾追加：

```sql

CREATE TABLE IF NOT EXISTS leaderboard_snapshot (
    period_type    VARCHAR(10) NOT NULL CHECK (period_type IN ('MONTH', 'YEAR')),
    period_value   VARCHAR(10) NOT NULL,
    rank_no        INT NOT NULL,
    employee_id    BIGINT NOT NULL REFERENCES employee(id) ON DELETE CASCADE,
    points         BIGINT NOT NULL,
    active_points  BIGINT NOT NULL,
    passive_points BIGINT NOT NULL,
    PRIMARY KEY (period_type, period_value, employee_id)
);

CREATE INDEX IF NOT EXISTS idx_leaderboard_snapshot_rank
    ON leaderboard_snapshot(period_type, period_value, rank_no);
```

- [ ] **Step 2: 写夹具**

`backend/src/test/java/com/jiaxian/analytics/leaderboard/LeaderboardTestFixtures.java`（普通类，由测试用注入的 Repository 构造）：

```java
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
```

> 注意：`PointsLedger` 的 `@Builder` 字段按 Plan 1 现有测试 `FactAndPointsRepositoryIT` 的写法（`employeeId`、`actionType`、`category`、`points`、`refId`、`occurTime`、`simulationBatchId`），`refId` 可以不设。

- [ ] **Step 3: 写失败的测试**

`backend/src/test/java/com/jiaxian/analytics/leaderboard/LeaderboardQueryRepositoryIT.java`：

```java
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
```

- [ ] **Step 4: 运行，确认失败**

Run: `cd backend && mvn test -Dtest=LeaderboardQueryRepositoryIT`
Expected: 编译失败（`LeaderboardQueryRepository` 不存在）。

- [ ] **Step 5: 写实现**

`backend/src/main/java/com/jiaxian/analytics/leaderboard/LeaderboardQueryRepository.java`：

```java
package com.jiaxian.analytics.leaderboard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/**
 * 榜单取数。实时榜对 points_ledger 的区间求和并用 RANK() 排名（并列同名次），
 * 快照榜读 leaderboard_snapshot。两条路径输出同一种 LeaderboardEntry、同样的排序。
 */
@Repository
public class LeaderboardQueryRepository {

    private static final int MAX_LIMIT = 100;

    private static final RowMapper<LeaderboardEntry> ENTRY_MAPPER = (rs, rowNum) -> new LeaderboardEntry(
        rs.getInt(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getLong(6));

    private final JdbcTemplate jdbc;

    public LeaderboardQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 区间 [start, nextStart) 内的实时榜，名次降序，并列按员工 id 升序。 */
    public List<LeaderboardEntry> live(LocalDate start, LocalDate nextStart, int limit) {
        requireSafeLimit(limit);
        String sql = "SELECT x.rank_no, x.employee_id, e.name, x.total_points, x.active_points, x.passive_points"
            + " FROM (SELECT employee_id,"
            + "              RANK() OVER (ORDER BY SUM(points) DESC) AS rank_no,"
            + "              SUM(points) AS total_points,"
            + "              SUM(CASE WHEN category = 'ACTIVE' THEN points ELSE 0 END) AS active_points,"
            + "              SUM(CASE WHEN category = 'PASSIVE' THEN points ELSE 0 END) AS passive_points"
            + "       FROM points_ledger WHERE occur_time >= ? AND occur_time < ? GROUP BY employee_id) x"
            + " JOIN employee e ON e.id = x.employee_id"
            + " ORDER BY x.rank_no, x.employee_id LIMIT " + limit;
        return jdbc.query(sql, ENTRY_MAPPER,
            Timestamp.valueOf(start.atStartOfDay()), Timestamp.valueOf(nextStart.atStartOfDay()));
    }

    public List<LeaderboardEntry> snapshot(PeriodType type, String periodValue, int limit) {
        requireSafeLimit(limit);
        String sql = "SELECT s.rank_no, s.employee_id, e.name, s.points, s.active_points, s.passive_points"
            + " FROM leaderboard_snapshot s JOIN employee e ON e.id = s.employee_id"
            + " WHERE s.period_type = ? AND s.period_value = ?"
            + " ORDER BY s.rank_no, s.employee_id LIMIT " + limit;
        return jdbc.query(sql, ENTRY_MAPPER, type.name(), periodValue);
    }

    public boolean snapshotExists(PeriodType type, String periodValue) {
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM leaderboard_snapshot WHERE period_type = ? AND period_value = ?",
            Long.class, type.name(), periodValue);
        return count != null && count > 0;
    }

    /** points_ledger 里至少有一行流水的周期起点（升序）。 */
    public List<LocalDate> periodsWithLedger(PeriodType type) {
        String unit = type == PeriodType.MONTH ? "month" : "year";
        String sql = "SELECT DISTINCT date_trunc('" + unit + "', occur_time) AS period_start"
            + " FROM points_ledger ORDER BY period_start";
        return jdbc.query(sql, (rs, rowNum) -> rs.getTimestamp(1).toLocalDateTime().toLocalDate());
    }

    private static void requireSafeLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            // LIMIT 以字面量写进 SQL（OpenGauss 不接受绑定参数形式的分页），所以必须先确认是安全的整数范围。
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
```

- [ ] **Step 6: 运行，确认通过**

Run: `cd backend && mvn test -Dtest=LeaderboardQueryRepositoryIT`
Expected: `Tests run: 9, Failures: 0, Errors: 0`

- [ ] **Step 7: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src/main/resources/schema.sql backend/src/main/java/com/jiaxian/analytics/leaderboard/LeaderboardQueryRepository.java backend/src/test/java/com/jiaxian/analytics/leaderboard
git commit -m "feat: add leaderboard snapshot table and query repository

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 快照重算服务

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/leaderboard/LeaderboardSnapshotService.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/leaderboard/LeaderboardSnapshotServiceIT.java`

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/jiaxian/analytics/leaderboard/LeaderboardSnapshotServiceIT.java`：

```java
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
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest=LeaderboardSnapshotServiceIT`
Expected: 编译失败（`LeaderboardSnapshotService` 不存在）。

- [ ] **Step 3: 写实现**

`backend/src/main/java/com/jiaxian/analytics/leaderboard/LeaderboardSnapshotService.java`：

```java
package com.jiaxian.analytics.leaderboard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;

/** 把已结束周期的榜单落进 leaderboard_snapshot。先删后插，同一周期重复重算结果完全一样。 */
@Service
public class LeaderboardSnapshotService {

    private final JdbcTemplate jdbc;
    private final LeaderboardQueryRepository repository;
    private final Clock clock;

    public LeaderboardSnapshotService(JdbcTemplate jdbc, LeaderboardQueryRepository repository, Clock clock) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.clock = clock;
    }

    /** 重算一期快照，返回写入行数。周期必须已经结束。 */
    @Transactional
    public int rebuild(PeriodType type, String periodValue) {
        LocalDate start = type.parse(periodValue);
        LocalDate nextStart = type.nextStart(start);
        if (nextStart.isAfter(LocalDate.now(clock))) {
            throw new InvalidLeaderboardQueryException("Period " + periodValue + " has not ended yet");
        }
        // 用解析后重新格式化的规范值写进 SQL 字面量（SELECT ? 这种裸参数 OpenGauss 推断不出类型）。
        String canonical = type.format(start);

        jdbc.update("DELETE FROM leaderboard_snapshot WHERE period_type = ? AND period_value = ?",
            type.name(), canonical);
        String insert = "INSERT INTO leaderboard_snapshot"
            + " (period_type, period_value, rank_no, employee_id, points, active_points, passive_points)"
            + " SELECT '" + type.name() + "', '" + canonical + "',"
            + "        RANK() OVER (ORDER BY SUM(points) DESC), employee_id, SUM(points),"
            + "        SUM(CASE WHEN category = 'ACTIVE' THEN points ELSE 0 END),"
            + "        SUM(CASE WHEN category = 'PASSIVE' THEN points ELSE 0 END)"
            + " FROM points_ledger WHERE occur_time >= ? AND occur_time < ? GROUP BY employee_id";
        return jdbc.update(insert,
            Timestamp.valueOf(start.atStartOfDay()), Timestamp.valueOf(nextStart.atStartOfDay()));
    }

    /**
     * 对所有"已结束且 points_ledger 里有流水"的月和年重算。放在同一个事务里
     * （rebuild 是自调用，事务靠这里的注解提供）。
     */
    @Transactional
    public SnapshotSummary rebuildAllEnded() {
        LocalDate today = LocalDate.now(clock);
        int months = 0;
        int years = 0;
        int rows = 0;
        for (PeriodType type : PeriodType.values()) {
            for (LocalDate start : repository.periodsWithLedger(type)) {
                if (type.nextStart(start).isAfter(today)) {
                    continue;
                }
                rows += rebuild(type, type.format(start));
                if (type == PeriodType.MONTH) {
                    months++;
                } else {
                    years++;
                }
            }
        }
        return new SnapshotSummary(months, years, rows);
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn test -Dtest=LeaderboardSnapshotServiceIT`
Expected: `Tests run: 8, Failures: 0, Errors: 0`

- [ ] **Step 5: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src/main/java/com/jiaxian/analytics/leaderboard/LeaderboardSnapshotService.java backend/src/test/java/com/jiaxian/analytics/leaderboard/LeaderboardSnapshotServiceIT.java
git commit -m "feat: add leaderboard snapshot rebuild service

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 定时重算

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/leaderboard/LeaderboardSnapshotScheduler.java`
- Modify: `backend/src/test/resources/application.properties`
- Test: `backend/src/test/java/com/jiaxian/analytics/leaderboard/LeaderboardSnapshotSchedulerTest.java`

- [ ] **Step 1: 写测试**

`backend/src/test/java/com/jiaxian/analytics/leaderboard/LeaderboardSnapshotSchedulerTest.java`：

```java
package com.jiaxian.analytics.leaderboard;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LeaderboardSnapshotSchedulerTest {

    private final LeaderboardSnapshotService snapshotService = mock(LeaderboardSnapshotService.class);

    private LeaderboardSnapshotScheduler schedulerAt(String instant) {
        return new LeaderboardSnapshotScheduler(snapshotService,
            Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    void rebuildsThePreviousMonthAndThePreviousYear() {
        schedulerAt("2026-10-04T00:20:00Z").rebuildPreviousPeriods();

        verify(snapshotService).rebuild(PeriodType.MONTH, "2026-09");
        verify(snapshotService).rebuild(PeriodType.YEAR, "2025");
    }

    @Test
    void onNewYearsDayThePreviousMonthIsDecember() {
        schedulerAt("2026-01-01T00:20:00Z").rebuildPreviousPeriods();

        verify(snapshotService).rebuild(PeriodType.MONTH, "2025-12");
        verify(snapshotService).rebuild(PeriodType.YEAR, "2025");
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest=LeaderboardSnapshotSchedulerTest`
Expected: 编译失败。

- [ ] **Step 3: 写实现**

`backend/src/main/java/com/jiaxian/analytics/leaderboard/LeaderboardSnapshotScheduler.java`：

```java
package com.jiaxian.analytics.leaderboard;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/** 每天凌晨重算"上一个月"和"上一年"的快照。幂等，所以漏跑一次也会在第二天自愈。 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "analytics.leaderboard.schedule.enabled", havingValue = "true", matchIfMissing = true)
public class LeaderboardSnapshotScheduler {

    private final LeaderboardSnapshotService snapshotService;
    private final Clock clock;

    public LeaderboardSnapshotScheduler(LeaderboardSnapshotService snapshotService, Clock clock) {
        this.snapshotService = snapshotService;
        this.clock = clock;
    }

    @Scheduled(cron = "${analytics.leaderboard.snapshot-cron:0 20 0 * * *}")
    public void rebuildPreviousPeriods() {
        LocalDate today = LocalDate.now(clock);
        LocalDate previousMonth = PeriodType.MONTH.containing(today).minusMonths(1);
        LocalDate previousYear = PeriodType.YEAR.containing(today).minusYears(1);
        snapshotService.rebuild(PeriodType.MONTH, PeriodType.MONTH.format(previousMonth));
        snapshotService.rebuild(PeriodType.YEAR, PeriodType.YEAR.format(previousYear));
    }
}
```

在 `backend/src/test/resources/application.properties` 末尾追加一行：

```properties
analytics.leaderboard.schedule.enabled=false
```

- [ ] **Step 4: 运行并提交**

Run: `cd backend && mvn test -Dtest=LeaderboardSnapshotSchedulerTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src/main/java/com/jiaxian/analytics/leaderboard/LeaderboardSnapshotScheduler.java backend/src/test/java/com/jiaxian/analytics/leaderboard/LeaderboardSnapshotSchedulerTest.java backend/src/test/resources/application.properties
git commit -m "feat: add scheduled leaderboard snapshot rebuild

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 榜单服务（选数据来源与校验）

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/leaderboard/LeaderboardService.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/leaderboard/LeaderboardServiceTest.java`

- [ ] **Step 1: 写测试**（Mockito 仓库 + 固定时钟，今天 = 2026-10-04）

`backend/src/test/java/com/jiaxian/analytics/leaderboard/LeaderboardServiceTest.java`：

```java
package com.jiaxian.analytics.leaderboard;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LeaderboardServiceTest {

    private static final List<LeaderboardEntry> ENTRIES = List.of(new LeaderboardEntry(1, 7, "员工-7", 300, 200, 100));

    private final LeaderboardQueryRepository repository = mock(LeaderboardQueryRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-04T08:00:00Z"), ZoneOffset.UTC);
    private final LeaderboardService service = new LeaderboardService(repository, clock);

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    @Test
    void defaultPeriodIsTheCurrentOneAndIsComputedLive() {
        when(repository.live(d(2026, 10, 1), d(2026, 11, 1), 50)).thenReturn(ENTRIES);

        Leaderboard board = service.leaderboard(PeriodType.MONTH, null, 50);

        assertThat(board.periodType()).isEqualTo(PeriodType.MONTH);
        assertThat(board.periodValue()).isEqualTo("2026-10");
        assertThat(board.status()).isEqualTo(Leaderboard.Status.IN_PROGRESS);
        assertThat(board.source()).isEqualTo(Leaderboard.Source.LIVE);
        assertThat(board.entries()).isEqualTo(ENTRIES);
    }

    @Test
    void explicitCurrentPeriodIsStillInProgress() {
        when(repository.live(d(2026, 1, 1), d(2027, 1, 1), 10)).thenReturn(ENTRIES);

        Leaderboard board = service.leaderboard(PeriodType.YEAR, "2026", 10);

        assertThat(board.periodValue()).isEqualTo("2026");
        assertThat(board.status()).isEqualTo(Leaderboard.Status.IN_PROGRESS);
        assertThat(board.source()).isEqualTo(Leaderboard.Source.LIVE);
    }

    @Test
    void endedPeriodWithASnapshotIsReadFromTheSnapshot() {
        when(repository.snapshotExists(PeriodType.MONTH, "2026-09")).thenReturn(true);
        when(repository.snapshot(PeriodType.MONTH, "2026-09", 50)).thenReturn(ENTRIES);

        Leaderboard board = service.leaderboard(PeriodType.MONTH, "2026-09", 50);

        assertThat(board.status()).isEqualTo(Leaderboard.Status.ARCHIVED);
        assertThat(board.source()).isEqualTo(Leaderboard.Source.SNAPSHOT);
        assertThat(board.entries()).isEqualTo(ENTRIES);
    }

    @Test
    void endedPeriodWithoutASnapshotFallsBackToLive() {
        when(repository.snapshotExists(PeriodType.MONTH, "2026-09")).thenReturn(false);
        when(repository.live(d(2026, 9, 1), d(2026, 10, 1), 50)).thenReturn(ENTRIES);

        Leaderboard board = service.leaderboard(PeriodType.MONTH, "2026-09", 50);

        assertThat(board.status()).isEqualTo(Leaderboard.Status.ARCHIVED);
        assertThat(board.source()).isEqualTo(Leaderboard.Source.LIVE);
        assertThat(board.entries()).isEqualTo(ENTRIES);
    }

    @Test
    void futurePeriodsAreRejected() {
        assertThatThrownBy(() -> service.leaderboard(PeriodType.MONTH, "2026-11", 50))
            .isInstanceOf(InvalidLeaderboardQueryException.class).hasMessageContaining("future");
        assertThatThrownBy(() -> service.leaderboard(PeriodType.YEAR, "2027", 50))
            .isInstanceOf(InvalidLeaderboardQueryException.class).hasMessageContaining("future");
    }

    @Test
    void malformedPeriodAndBadLimitAreRejected() {
        assertThatThrownBy(() -> service.leaderboard(PeriodType.MONTH, "2026-9", 50))
            .isInstanceOf(InvalidLeaderboardQueryException.class);
        assertThatThrownBy(() -> service.leaderboard(PeriodType.MONTH, "2026-09", 0))
            .isInstanceOf(InvalidLeaderboardQueryException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> service.leaderboard(PeriodType.MONTH, "2026-09", 101))
            .isInstanceOf(InvalidLeaderboardQueryException.class).hasMessageContaining("limit");
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest=LeaderboardServiceTest`
Expected: 编译失败。

- [ ] **Step 3: 写实现**

`backend/src/main/java/com/jiaxian/analytics/leaderboard/LeaderboardService.java`：

```java
package com.jiaxian.analytics.leaderboard;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** 校验参数并选择数据来源：进行中实时算；已结束读快照；已结束但没有快照回退实时算。 */
@Service
public class LeaderboardService {

    static final int MAX_LIMIT = 100;

    private final LeaderboardQueryRepository repository;
    private final Clock clock;

    public LeaderboardService(LeaderboardQueryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** period 为 null 表示当前周期。 */
    public Leaderboard leaderboard(PeriodType type, String period, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidLeaderboardQueryException("limit must be between 1 and " + MAX_LIMIT);
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate start = period == null ? type.containing(today) : type.parse(period);
        if (start.isAfter(today)) {
            throw new InvalidLeaderboardQueryException("period must not be in the future");
        }
        String value = type.format(start);
        LocalDate nextStart = type.nextStart(start);

        if (nextStart.isAfter(today)) {
            return new Leaderboard(type, value, Leaderboard.Status.IN_PROGRESS, Leaderboard.Source.LIVE,
                repository.live(start, nextStart, limit));
        }
        if (repository.snapshotExists(type, value)) {
            return new Leaderboard(type, value, Leaderboard.Status.ARCHIVED, Leaderboard.Source.SNAPSHOT,
                repository.snapshot(type, value, limit));
        }
        List<LeaderboardEntry> entries = repository.live(start, nextStart, limit);
        return new Leaderboard(type, value, Leaderboard.Status.ARCHIVED, Leaderboard.Source.LIVE, entries);
    }
}
```

- [ ] **Step 4: 运行并提交**

Run: `cd backend && mvn test -Dtest=LeaderboardServiceTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0`

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src/main/java/com/jiaxian/analytics/leaderboard/LeaderboardService.java backend/src/test/java/com/jiaxian/analytics/leaderboard/LeaderboardServiceTest.java
git commit -m "feat: add leaderboard service choosing live or snapshot source

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 6: HTTP 接口与 400 处理

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/leaderboard/LeaderboardExceptionHandler.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/leaderboard/LeaderboardController.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/leaderboard/LeaderboardAdminController.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/leaderboard/LeaderboardControllerIT.java`

- [ ] **Step 1: 写测试**（HTTP，不依赖具体数据：2019 年没有真实数据；管理接口"全部重算"只会改派生的快照表）

`backend/src/test/java/com/jiaxian/analytics/leaderboard/LeaderboardControllerIT.java`：

```java
package com.jiaxian.analytics.leaderboard;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaderboardControllerIT {

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void assertGetBadRequest(String path, String expectedMessagePart) {
        ResponseEntity<String> response = rest.getForEntity(url(path), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains(expectedMessagePart);
    }

    @Test
    void archivedPeriodWithoutDataReturnsAnEmptyBoard() {
        ResponseEntity<JsonNode> response = rest.getForEntity(
            url("/api/leaderboard/month?period=2019-03&limit=10"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body.get("periodType").asText()).isEqualTo("MONTH");
        assertThat(body.get("periodValue").asText()).isEqualTo("2019-03");
        assertThat(body.get("status").asText()).isEqualTo("ARCHIVED");
        assertThat(body.get("source").asText()).isEqualTo("LIVE");   // 2019-03 没有快照，回退实时
        assertThat(body.get("entries").isArray()).isTrue();
        assertThat(body.get("entries")).isEmpty();
    }

    @Test
    void defaultsToTheCurrentPeriodInProgress() {
        ResponseEntity<JsonNode> response = rest.getForEntity(url("/api/leaderboard/year"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("periodValue").asText()).isEqualTo(String.valueOf(LocalDate.now().getYear()));
        assertThat(response.getBody().get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(response.getBody().get("source").asText()).isEqualTo("LIVE");
    }

    @Test
    void leaderboardRejectsBadParameters() {
        assertGetBadRequest("/api/leaderboard/week", "month");
        assertGetBadRequest("/api/leaderboard/MONTH", "month");
        assertGetBadRequest("/api/leaderboard/month?period=2019-3", "yyyy-MM");
        assertGetBadRequest("/api/leaderboard/year?period=2999", "future");
        assertGetBadRequest("/api/leaderboard/month?limit=0", "limit");
        assertGetBadRequest("/api/leaderboard/month?limit=101", "limit");
        assertGetBadRequest("/api/leaderboard/month?limit=abc", "limit");
    }

    @Test
    void snapshotEndpointRebuildsOnePeriod() {
        ResponseEntity<JsonNode> response = rest.postForEntity(
            url("/api/admin/analytics/leaderboard/snapshot?periodType=MONTH&periodValue=2019-03"), null, JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("periodType").asText()).isEqualTo("MONTH");
        assertThat(response.getBody().get("periodValue").asText()).isEqualTo("2019-03");
        assertThat(response.getBody().get("rowsWritten").asInt()).isZero();   // 2019 年没有任何流水
    }

    @Test
    void snapshotEndpointWithoutParametersRebuildsEverything() {
        ResponseEntity<JsonNode> response = rest.postForEntity(
            url("/api/admin/analytics/leaderboard/snapshot"), null, JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().has("months")).isTrue();
        assertThat(response.getBody().has("years")).isTrue();
        assertThat(response.getBody().has("rowsWritten")).isTrue();
    }

    @Test
    void snapshotEndpointRejectsBadRequests() {
        String base = "/api/admin/analytics/leaderboard/snapshot";
        String currentMonth = PeriodType.MONTH.format(PeriodType.MONTH.containing(LocalDate.now()));

        assertPostBadRequest(base + "?periodType=MONTH", "together");
        assertPostBadRequest(base + "?periodValue=2019-03", "together");
        assertPostBadRequest(base + "?periodType=MONTH&periodValue=" + currentMonth, "not ended");
        assertPostBadRequest(base + "?periodType=DAY&periodValue=2019-03", "periodType");
    }

    private void assertPostBadRequest(String path, String expectedMessagePart) {
        ResponseEntity<String> response = rest.postForEntity(url(path), null, String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains(expectedMessagePart);
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest=LeaderboardControllerIT`
Expected: 测试失败（接口还不存在，返回 404）。

- [ ] **Step 3: 写实现**

`backend/src/main/java/com/jiaxian/analytics/leaderboard/LeaderboardExceptionHandler.java`：

```java
package com.jiaxian.analytics.leaderboard;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 只作用于榜单接口：参数问题统一返回 400 + 一句说明原因的文字。 */
@RestControllerAdvice(assignableTypes = {LeaderboardController.class, LeaderboardAdminController.class})
public class LeaderboardExceptionHandler {

    @ExceptionHandler(InvalidLeaderboardQueryException.class)
    public ResponseEntity<String> invalidQuery(InvalidLeaderboardQueryException e) {
        return badRequest(e.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<String> typeMismatch(MethodArgumentTypeMismatchException e) {
        return badRequest("Invalid value for parameter '" + e.getName() + "'");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<String> missingParameter(MissingServletRequestParameterException e) {
        return badRequest("Missing required parameter '" + e.getParameterName() + "'");
    }

    private static ResponseEntity<String> badRequest(String message) {
        return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body(message);
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/leaderboard/LeaderboardController.java`：

```java
package com.jiaxian.analytics.leaderboard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/leaderboard")
public class LeaderboardController {

    private final LeaderboardService service;

    public LeaderboardController(LeaderboardService service) {
        this.service = service;
    }

    /**
     * 月榜/年榜。type 只接受小写 month / year；period 省略表示当前周期；
     * 进行中的周期实时计算，已结束的周期读快照（缺快照时回退实时计算）。
     */
    @GetMapping("/{type}")
    public Leaderboard leaderboard(@PathVariable String type,
                                   @RequestParam(required = false) String period,
                                   @RequestParam(defaultValue = "50") int limit) {
        return service.leaderboard(PeriodType.fromPath(type), period, limit);
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/leaderboard/LeaderboardAdminController.java`：

```java
package com.jiaxian.analytics.leaderboard;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/analytics/leaderboard")
public class LeaderboardAdminController {

    private final LeaderboardSnapshotService snapshotService;

    public LeaderboardAdminController(LeaderboardSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    /**
     * 不带参数：重算所有已结束且有流水的月和年（首次回填完历史数据后调用一次）。
     * 带 periodType 和 periodValue：只重算那一期，周期必须已经结束。
     */
    @PostMapping("/snapshot")
    public Object snapshot(@RequestParam(required = false) PeriodType periodType,
                           @RequestParam(required = false) String periodValue) {
        if (periodType == null && periodValue == null) {
            return snapshotService.rebuildAllEnded();
        }
        if (periodType == null || periodValue == null) {
            throw new InvalidLeaderboardQueryException("periodType and periodValue must be given together");
        }
        return new SnapshotRebuildResult(periodType, periodValue, snapshotService.rebuild(periodType, periodValue));
    }
}
```

- [ ] **Step 4: 运行并提交**

Run: `cd backend && mvn test -Dtest=LeaderboardControllerIT`
Expected: `Tests run: 6, Failures: 0, Errors: 0`

如果 `periodType=DAY` 的 400 里没有 `periodType` 字样，说明类型转换异常没走处理器；检查 `LeaderboardExceptionHandler` 的 `assignableTypes` 是否包含两个控制器。

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src/main/java/com/jiaxian/analytics/leaderboard backend/src/test/java/com/jiaxian/analytics/leaderboard/LeaderboardControllerIT.java
git commit -m "feat: add leaderboard and snapshot admin HTTP endpoints

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 端到端一致性测试

**Files:**
- Test: `backend/src/test/java/com/jiaxian/analytics/leaderboard/LeaderboardEndToEndIT.java`

核心断言：历史回填 → 全部重算快照 → 每个已结束周期的快照积分总和等于流水里同周期的总和；快照榜与实时榜逐行一致；再次回填后快照随员工级联清空。

- [ ] **Step 1: 写测试**

`backend/src/test/java/com/jiaxian/analytics/leaderboard/LeaderboardEndToEndIT.java`：

```java
package com.jiaxian.analytics.leaderboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.jiaxian.analytics.simulator.HistoricalBackfillService;
import com.jiaxian.analytics.simulator.SimulationConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 OpenGauss + 真实 HTTP：回填 → 全部重算快照 → 校验快照总和、快照榜 = 实时榜、级联清空。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaderboardEndToEndIT {

    @Autowired private HistoricalBackfillService backfillService;
    @Autowired private LeaderboardQueryRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void backfill() {
        backfillService.runBackfill(SimulationConfig.builder()
            .batchId("leaderboard-e2e")
            .employeeCount(30)
            .accountCount(12)
            .teamAccountRatio(0.25)
            .contentCount(60)
            .historyStart(LocalDate.of(2025, 1, 1))
            .historyEnd(LocalDate.of(2026, 3, 1))
            .randomSeed(21)
            .build());
    }

    private Map<String, Long> totals(String sql) {
        Map<String, Long> totals = new TreeMap<>();
        jdbc.query(sql, rs -> {
            totals.put(rs.getString(1), rs.getLong(2));
        });
        return totals;
    }

    /** 只保留已结束的周期（下一周期起点不晚于今天）。 */
    private Map<String, Long> endedOnly(PeriodType type, Map<String, Long> byPeriod) {
        LocalDate today = LocalDate.now();
        Map<String, Long> ended = new TreeMap<>();
        byPeriod.forEach((period, total) -> {
            if (!type.nextStart(type.parse(period)).isAfter(today)) {
                ended.put(period, total);
            }
        });
        return ended;
    }

    @Test
    void snapshotsAgreeWithTheLedgerAndTheLiveBoard() {
        backfill();

        ResponseEntity<JsonNode> rebuild = rest.postForEntity(
            url("/api/admin/analytics/leaderboard/snapshot"), null, JsonNode.class);
        assertThat(rebuild.getStatusCode().value()).isEqualTo(200);
        assertThat(rebuild.getBody().get("months").asInt()).isGreaterThan(0);
        assertThat(rebuild.getBody().get("years").asInt()).isGreaterThan(0);

        // 已结束周期的快照总和必须等于流水里同周期的总和（进行中的周期，比如当前这一年，按设计不落快照）
        assertThat(totals("SELECT period_value, SUM(points) FROM leaderboard_snapshot"
            + " WHERE period_type = 'MONTH' GROUP BY period_value"))
            .isEqualTo(endedOnly(PeriodType.MONTH,
                totals("SELECT to_char(occur_time, 'YYYY-MM'), SUM(points) FROM points_ledger GROUP BY 1")));
        assertThat(totals("SELECT period_value, SUM(points) FROM leaderboard_snapshot"
            + " WHERE period_type = 'YEAR' GROUP BY period_value"))
            .isEqualTo(endedOnly(PeriodType.YEAR,
                totals("SELECT to_char(occur_time, 'YYYY'), SUM(points) FROM points_ledger GROUP BY 1")));

        // 取快照人数最多的那个月：HTTP 返回的是快照，并且和实时榜逐行一致
        String busiestMonth = jdbc.queryForObject(
            "SELECT period_value FROM leaderboard_snapshot WHERE period_type = 'MONTH'"
                + " GROUP BY period_value ORDER BY COUNT(*) DESC, period_value LIMIT 1", String.class);
        JsonNode board = rest.getForEntity(
            url("/api/leaderboard/month?period=" + busiestMonth + "&limit=100"), JsonNode.class).getBody();
        assertThat(board.get("status").asText()).isEqualTo("ARCHIVED");
        assertThat(board.get("source").asText()).isEqualTo("SNAPSHOT");

        LocalDate start = PeriodType.MONTH.parse(busiestMonth);
        List<LeaderboardEntry> live = repository.live(start, PeriodType.MONTH.nextStart(start), 100);
        List<LeaderboardEntry> fromHttp = new ArrayList<>();
        for (JsonNode entry : board.get("entries")) {
            fromHttp.add(new LeaderboardEntry(entry.get("rank").asInt(), entry.get("employeeId").asLong(),
                entry.get("name").asText(), entry.get("points").asLong(),
                entry.get("activePoints").asLong(), entry.get("passivePoints").asLong()));
        }
        assertThat(live).isNotEmpty();
        assertThat(fromHttp).isEqualTo(live);

        // 排名并列时名次相同、名次不降序
        int previousRank = 0;
        for (LeaderboardEntry entry : fromHttp) {
            assertThat(entry.rank()).isGreaterThanOrEqualTo(previousRank);
            assertThat(entry.points()).isEqualTo(entry.activePoints() + entry.passivePoints());
            previousRank = entry.rank();
        }

        // 再次回填会 deleteAll() 全部员工：快照必须随员工级联清空
        backfill();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM leaderboard_snapshot", Long.class)).isZero();
    }
}
```

- [ ] **Step 2: 运行**

Run: `cd backend && mvn test -Dtest=LeaderboardEndToEndIT`
Expected: `Tests run: 1, Failures: 0, Errors: 0`

失败时的排查顺序：
1. 月/年总和对不上：检查流水里是否有今天及以后的记录（回填后其他测试残留的实时事件）；`points_ledger` 的 `to_char(occur_time, 'YYYY-MM')` 在 A 兼容模式下是否返回预期格式。
2. `source` 不是 `SNAPSHOT`：确认 `busiestMonth` 已结束（早于本月）。
3. 年度对比里出现"多出的当前年份"：当前年份进行中、不落快照，对比前必须用 `endedOnly` 过滤。

- [ ] **Step 3: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src/test/java/com/jiaxian/analytics/leaderboard/LeaderboardEndToEndIT.java
git commit -m "test: add end-to-end leaderboard consistency test

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 8: 手工验证与收尾

**Files:**
- Modify: `docs/superpowers/specs/2026-09-23-community-analytics-design.md`
- Modify: `docs/superpowers/specs/2026-10-04-leaderboard-design.md`（状态行）

- [ ] **Step 1: 启动应用并回填**

```bash
cd backend && mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8093
```

Expected: 日志出现 `Started AnalyticsApplication`。另开终端：

```bash
curl -s -X POST localhost:8093/api/admin/simulator/backfill -H 'Content-Type: application/json' \
  -d '{"batchId":"manual-lb","employeeCount":50,"accountCount":20,"teamAccountRatio":0.25,"contentCount":300,"historyStart":"2024-10-01","historyEnd":"2026-09-01","randomSeed":7}'
curl -s -X POST localhost:8093/api/admin/analytics/leaderboard/snapshot
```

Expected: 第二条返回 `{"months":N,"years":2,"rowsWritten":M}`（months 约 24 到 25，years 为 2024 和 2025）。

- [ ] **Step 2: 调用接口**

```bash
curl -s 'localhost:8093/api/leaderboard/month?period=2026-03&limit=5'
curl -s 'localhost:8093/api/leaderboard/year?period=2025&limit=5'
curl -s 'localhost:8093/api/leaderboard/month?limit=3'
curl -s -i 'localhost:8093/api/leaderboard/month?period=2999-01'
```

Expected：
- 2026-03 月榜 `status:ARCHIVED, source:SNAPSHOT`，名次按积分降序，每行 `points = activePoints + passivePoints`；
- 2025 年榜同样是快照；
- 当前月（2026-10）`status:IN_PROGRESS, source:LIVE`，`entries` 可能为空（回填只到 2026-09 + 30 天的浏览/互动，这里的流水以回填数据为准）；
- 最后一条返回 `HTTP/1.1 400` 和 "period must not be in the future"。

人工看一眼：同一员工的月榜名次和年榜名次是否合理（年榜积分 ≥ 任一月积分）。

- [ ] **Step 3: 关闭应用**

```bash
pkill -f spring-boot:run
sleep 3
lsof -i :8093 || echo "port 8093 free"
```

Expected: 输出 `port 8093 free`。

- [ ] **Step 4: 同步母设计文档并更新状态**

在 `docs/superpowers/specs/2026-09-23-community-analytics-design.md` 的 3.4 汇总表表格**之后**加一行说明：

```markdown
> 实现时的取舍（见 [榜单设计](2026-10-04-leaderboard-design.md)）：不建 `points_summary_monthly/yearly`，当期榜单查询时直接对 `points_ledger` 区间求和，`leaderboard_snapshot` 里同时带主动/被动拆分；`rank` 列实际命名为 `rank_no`（`rank` 是保留字）。
```

并在同文件 3.5 节"Redis 缓存 key 设计"列表之后加一行：

```markdown
> 实现时的取舍：`leaderboard:month/year` Redis 实时榜未实现——榜单走"进行中实时求和 + 已结束读快照"，Plan 4 不改 Kafka 消费者。
```

把 `docs/superpowers/specs/2026-10-04-leaderboard-design.md` 的 `- 状态：已通过讨论确认` 改为 `- 状态：已实现（实施计划见 [积分排行榜](../plans/2026-10-04-leaderboard.md)）`。

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add docs
git commit -m "docs: record leaderboard design decisions and mark spec implemented

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

之后按 `superpowers:finishing-a-development-branch` 收尾；推送到 GitHub 只在用户要求时进行。

---

## Self-Review

**1. 设计文档覆盖：**
- 第 3 节口径（区间求和、左闭右开、并列名次、不上榜条件、只算当期）→ Task 2（边界、并列、只算当期、0 流水不上榜）。
- 第 4 节数据模型（`rank_no`、级联、主键、索引）→ Task 2 建表；级联 → Task 3 的 `snapshotRowsAreRemovedWhenTheEmployeeIsDeleted` + Task 7。
- 第 5 节快照生成（`rebuild`、只允许已结束周期、`rebuildAllEnded`、定时、手动、单期）→ Task 3、4、6。
- 第 6 节查询接口（默认当前周期、`limit`、响应结构、数据来源三种情况、路径小写、400 错误）→ Task 5、6。
- 第 7 节组件划分 → 文件结构表。
- 第 8 节测试策略：纯逻辑（Task 1）、真实库 IT（Task 2、3）、服务层单测（Task 5）、接口（Task 6）、端到端（Task 7）。
- 第 2 节与母设计文档的差异 → Task 8 Step 4 同步文档。

**2. 占位符扫描：** 无 TBD/TODO。Task 3 Step 1 的提示里指出了一个我在写计划时算错的下限（`4+1+1+4+5` 应为 `1+1+4+1+5`），Step 2 给出了替换；执行时直接在 Step 1 的代码里写正确值也可以。

**3. 类型一致性：** `PeriodType`（`parse`/`format`/`containing`/`nextStart`/`fromPath`）在 Task 1 定义，Task 2–7 使用的名字一致；`LeaderboardEntry(rank, employeeId, name, points, activePoints, passivePoints)` 在 Task 1 定义，夹具 `entry(...)`、Repository 的行映射、端到端测试的 JSON 还原都按同样的顺序；`LeaderboardQueryRepository` 的 `live(start, nextStart, limit)` / `snapshot(type, value, limit)` / `snapshotExists(type, value)` / `periodsWithLedger(type)` 在 Task 2 定义，Task 3/5/7 的调用签名一致；`LeaderboardSnapshotService.rebuild(type, value)` / `rebuildAllEnded()` 在 Task 3 定义，Task 4/6 使用；`Leaderboard.Status`/`Source` 嵌套枚举在 Task 1 定义，Task 5/6 使用。
