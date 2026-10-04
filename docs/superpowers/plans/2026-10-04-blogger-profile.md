# 个人博主详情页接口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付个人博主详情页的三个只读接口：`profile`（基本信息、当前分层、RFM 历史轨迹、标签变化点、最近事件）、`activity`（发帖/评论/登录次数分布）、`points-trend`（主动/被动积分堆叠趋势）。

**Architecture:** 全部在现有 `com.jiaxian.analytics.kol` 包里，只读、不建新表。SQL 只按天取数（`date_trunc('day', …)`），分桶补零在 Java 里做，复用 PV 分析的 `Granularity`；周期生成（`PeriodAxis`）和标签变化点（`SegmentTrajectory`）是纯逻辑，可以手算数字做单元测试。`BloggerProfileService` 持有 `Clock`，解析默认范围并组装结果。

**Tech Stack:** Spring Boot 3.2.5 (Java 17), JdbcTemplate, OpenGauss 5.0.0（A 兼容模式）, JUnit 5 + Mockito + AssertJ。

**设计文档：** `docs/superpowers/specs/2026-10-04-blogger-profile-design.md`（先读它）。

---

## 必读：约定与踩坑记录

沿用 Plan 3、4、5 的"约定与踩坑记录"。与本计划直接相关的：

1. **日期参数一律绑定 `java.sql.Timestamp`**；`DATE` 列在 A 兼容模式下实际是 `timestamp`，读取用 `getTimestamp(…).toLocalDateTime().toLocalDate()`；按天截断用 `date_trunc('day', col)`，**不要** `CAST(… AS DATE)`；`GROUP BY` 里写完整表达式，不要用序号。
2. **`LIMIT` 必须是校验过的整数字面量**，不能绑定参数。
3. **`@Repository` 会把 `IllegalArgumentException` 包成 `InvalidDataAccessApiUsageException`**；参数校验放在 Service 里抛自己的异常，Repository 里的越界检查只是兜底。
4. **集成测试 `*IT` 连真实 OpenGauss**，优先 `@Transactional`；夹具日期统一用 **2019 年**，名字用"KOL测试"前缀。走 HTTP 的测试不能依赖回滚，要自己清理。
5. **每个任务完成后都跑完整 `cd backend && mvn test`**；本计划不改 `pom.xml`、不改 `schema.sql`。
6. 端口 8080 被无关进程占用——手工启动应用时用 `--server.port=8093`，用完 `pkill -f spring-boot:run` 并确认 `lsof -i :8093` 为空（可能要等几秒）。
7. `profile` 的 `current`/`history` 依赖"全局最近一次重算"（`KolRfmRepository.latestAsOf()`），数据库里可能有其他测试或手工运行留下的记录，所以**依赖它的逻辑用 Mockito 单测验证**，集成测试只用显式日期。
8. 校验顺序：先校验参数（400），再查员工是否存在（404）。

## 文件结构

新建（`backend/src/main/java/com/jiaxian/analytics/kol/`，测试在 `backend/src/test/java/com/jiaxian/analytics/kol/`）：

| 文件 | 职责 |
|---|---|
| `EmployeeNotFoundException.java` | 404 |
| `EmployeeInfo.java`、`AlertDetail.java`、`CurrentStanding.java`、`HistoryPoint.java`、`SegmentChange.java`、`RecentEvent.java`、`BloggerProfile.java` | `profile` 响应记录 |
| `ActivityPoint.java`、`ActivitySeries.java`、`PointsTrendPoint.java`、`PointsTrend.java` | 两个图表响应记录 |
| `PeriodAxis.java` | 纯逻辑：按粒度生成连续周期，带上限 |
| `SegmentTrajectory.java` | 纯逻辑：标签变化点 |
| `BloggerProfileRepository.java` | 员工、当前行、历史行、按天活跃次数/积分、最近事件 |
| `BloggerProfileService.java` | 校验、默认范围、组装、分桶补零 |
| `BloggerProfileController.java` | 三个接口 |

修改：`KolExceptionHandler.java`（加新控制器和 404 映射）、母设计文档（状态与一句说明）、本计划对应设计文档的状态行。

测试：`PeriodAxisTest`、`SegmentTrajectoryTest`、`BloggerProfileRepositoryIT`、`BloggerProfileServiceTest`、`BloggerProfileControllerIT`、`BloggerProfileEndToEndIT`。

---

### Task 1: 记录类、异常与两个纯逻辑组件

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/kol/{EmployeeNotFoundException,EmployeeInfo,AlertDetail,CurrentStanding,HistoryPoint,SegmentChange,RecentEvent,BloggerProfile,ActivityPoint,ActivitySeries,PointsTrendPoint,PointsTrend,PeriodAxis,SegmentTrajectory}.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/kol/PeriodAxisTest.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/kol/SegmentTrajectoryTest.java`

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/jiaxian/analytics/kol/PeriodAxisTest.java`：

```java
package com.jiaxian.analytics.kol;

import com.jiaxian.analytics.pv.Granularity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeriodAxisTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 10);   // 周三

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    @Test
    void weekPeriodsExpandFromToTheMondayAndMarkTheCurrentWeekIncomplete() {
        // from=06-03(周三) 向前扩展到周一 06-01；06-08 这一周今天(06-10)还在里面
        List<PeriodAxis.Period> periods =
            PeriodAxis.periods(Granularity.WEEK, d(2026, 6, 3), d(2026, 6, 9), TODAY, 400);

        assertThat(periods).containsExactly(
            new PeriodAxis.Period(d(2026, 6, 1), true),
            new PeriodAxis.Period(d(2026, 6, 8), false));
    }

    @Test
    void toBeyondTodayIsClamped() {
        List<PeriodAxis.Period> periods =
            PeriodAxis.periods(Granularity.DAY, d(2026, 6, 8), d(2026, 6, 30), TODAY, 400);

        assertThat(periods).containsExactly(
            new PeriodAxis.Period(d(2026, 6, 8), true),
            new PeriodAxis.Period(d(2026, 6, 9), true),
            new PeriodAxis.Period(d(2026, 6, 10), false));
    }

    @Test
    void monthPeriodsAreContinuous() {
        List<PeriodAxis.Period> periods =
            PeriodAxis.periods(Granularity.MONTH, d(2026, 1, 15), d(2026, 3, 20), TODAY, 400);

        assertThat(periods).containsExactly(
            new PeriodAxis.Period(d(2026, 1, 1), true),
            new PeriodAxis.Period(d(2026, 2, 1), true),
            new PeriodAxis.Period(d(2026, 3, 1), true));
    }

    @Test
    void aRangeEntirelyInTheFutureHasNoPeriods() {
        assertThat(PeriodAxis.periods(Granularity.MONTH, d(2026, 7, 1), d(2026, 7, 31), TODAY, 400)).isEmpty();
    }

    @Test
    void theNumberOfPeriodsIsCapped() {
        // 2026-05-12 ~ 2026-06-10 共 30 天
        assertThat(PeriodAxis.periods(Granularity.DAY, d(2026, 5, 12), d(2026, 6, 10), TODAY, 30)).hasSize(30);
        assertThatThrownBy(() -> PeriodAxis.periods(Granularity.DAY, d(2026, 5, 12), d(2026, 6, 10), TODAY, 29))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("29");
        // 巨大范围不会先生成再检查：遇到上限就停
        assertThatThrownBy(() -> PeriodAxis.periods(Granularity.DAY, d(1000, 1, 1), d(2026, 6, 10), TODAY, 400))
            .isInstanceOf(InvalidKolQueryException.class);
    }
}
```

`backend/src/test/java/com/jiaxian/analytics/kol/SegmentTrajectoryTest.java`：

```java
package com.jiaxian.analytics.kol;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentTrajectoryTest {

    private static HistoryPoint point(String asOf, Segment segment) {
        return new HistoryPoint(LocalDate.parse(asOf), 1, segment, segment.displayName(), false, 3, 3, 3);
    }

    @Test
    void noHistoryOrASinglePeriodHasNoChanges() {
        assertThat(SegmentTrajectory.changes(List.of())).isEmpty();
        assertThat(SegmentTrajectory.changes(List.of(point("2026-03-01", Segment.STAR_KOL)))).isEmpty();
    }

    @Test
    void anUnchangedSegmentHasNoChanges() {
        assertThat(SegmentTrajectory.changes(List.of(
            point("2026-02-15", Segment.STEADY), point("2026-02-22", Segment.STEADY),
            point("2026-03-01", Segment.STEADY)))).isEmpty();
    }

    @Test
    void everySegmentChangeIsRecordedAtThePeriodWhereItHappened() {
        List<SegmentChange> changes = SegmentTrajectory.changes(List.of(
            point("2026-02-08", Segment.RISING),
            point("2026-02-15", Segment.STEADY),
            point("2026-02-22", Segment.STEADY),
            point("2026-03-01", Segment.STAR_KOL),
            point("2026-03-08", Segment.CHURN_RISK)));

        assertThat(changes).containsExactly(
            new SegmentChange(LocalDate.parse("2026-02-15"), Segment.RISING, Segment.STEADY),
            new SegmentChange(LocalDate.parse("2026-03-01"), Segment.STEADY, Segment.STAR_KOL),
            new SegmentChange(LocalDate.parse("2026-03-08"), Segment.STAR_KOL, Segment.CHURN_RISK));
    }

    @Test
    void goingBackAndForthIsRecordedEachTime() {
        List<SegmentChange> changes = SegmentTrajectory.changes(List.of(
            point("2026-02-15", Segment.STEADY), point("2026-02-22", Segment.OBSERVE),
            point("2026-03-01", Segment.STEADY)));

        assertThat(changes).hasSize(2);
        assertThat(changes.get(1).from()).isEqualTo(Segment.OBSERVE);
        assertThat(changes.get(1).to()).isEqualTo(Segment.STEADY);
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest='PeriodAxisTest,SegmentTrajectoryTest'`
Expected: 编译失败。

- [ ] **Step 3: 写实现**

`backend/src/main/java/com/jiaxian/analytics/kol/EmployeeNotFoundException.java`：

```java
package com.jiaxian.analytics.kol;

/** 员工不存在，对应 HTTP 404。 */
public class EmployeeNotFoundException extends RuntimeException {

    public EmployeeNotFoundException(long id) {
        super("Employee " + id + " does not exist");
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/EmployeeInfo.java`：

```java
package com.jiaxian.analytics.kol;

import java.time.LocalDate;

public record EmployeeInfo(long id, String name, String dept, LocalDate joinDate) {
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/AlertDetail.java`：

```java
package com.jiaxian.analytics.kol;

/** 触发流失预警时的详情：触发的指标、近期日均、基线日均、下降比例。 */
public record AlertDetail(ChurnSignal.Metric metric, double recentDaily, double baselineDaily, double dropRate) {
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/CurrentStanding.java`：

```java
package com.jiaxian.analytics.kol;

import java.time.LocalDate;

/** 员工在最近一次重算里的状态；alert 只在触发预警时有值。 */
public record CurrentStanding(LocalDate asOf, int rank, Segment segment, String segmentName, boolean churnAlert,
                              String recommendation, long rollingPoints12m, Integer recencyDays,
                              int frequency, long monetary, int rScore, int fScore, int mScore,
                              AlertDetail alert) {
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/HistoryPoint.java`：

```java
package com.jiaxian.analytics.kol;

import java.time.LocalDate;

/** 某次每周重算里这位员工的一行（用来画 RFM 分数和标签的历史走势）。 */
public record HistoryPoint(LocalDate asOf, int rank, Segment segment, String segmentName, boolean churnAlert,
                           int rScore, int fScore, int mScore) {
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/SegmentChange.java`：

```java
package com.jiaxian.analytics.kol;

import java.time.LocalDate;

/** 标签变化点：asOf 是发生变化的那一期。 */
public record SegmentChange(LocalDate asOf, Segment from, Segment to) {
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/RecentEvent.java`：

```java
package com.jiaxian.analytics.kol;

import java.time.LocalDateTime;

/** 最近的一次发帖/评论/登录；refId 可为 null（比如登录）。 */
public record RecentEvent(LocalDateTime time, String type, Long refId) {
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/BloggerProfile.java`：

```java
package com.jiaxian.analytics.kol;

import java.util.List;

/**
 * 博主详情。current 为 null 表示该员工不在最近一次重算的名单里（或还没有任何重算记录）。
 */
public record BloggerProfile(EmployeeInfo employee, CurrentStanding current, List<HistoryPoint> history,
                             List<SegmentChange> segmentChanges, List<RecentEvent> recentEvents) {
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/ActivityPoint.java`：

```java
package com.jiaxian.analytics.kol;

import java.time.LocalDate;

public record ActivityPoint(LocalDate periodStart, long posts, long comments, long logins, boolean complete) {
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/ActivitySeries.java`：

```java
package com.jiaxian.analytics.kol;

import com.jiaxian.analytics.pv.Granularity;

import java.time.LocalDate;
import java.util.List;

public record ActivitySeries(Granularity granularity, LocalDate from, LocalDate to, List<ActivityPoint> points) {
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/PointsTrendPoint.java`：

```java
package com.jiaxian.analytics.kol;

import java.time.LocalDate;

public record PointsTrendPoint(LocalDate periodStart, long activePoints, long passivePoints, long total,
                               boolean complete) {
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/PointsTrend.java`：

```java
package com.jiaxian.analytics.kol;

import com.jiaxian.analytics.pv.Granularity;

import java.time.LocalDate;
import java.util.List;

public record PointsTrend(Granularity granularity, LocalDate from, LocalDate to, List<PointsTrendPoint> points) {
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/PeriodAxis.java`：

```java
package com.jiaxian.analytics.kol;

import com.jiaxian.analytics.pv.Granularity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯逻辑：按粒度生成连续周期。from 向前扩展到所在周期起点，to 超过今天按今天算，
 * 周期的最后一天早于今天才算已结束；周期数超过 maxPeriods 立即停止并报 400（不会先生成再检查）。
 */
public final class PeriodAxis {

    public record Period(LocalDate start, boolean complete) {
    }

    private PeriodAxis() {
    }

    public static List<Period> periods(Granularity granularity, LocalDate from, LocalDate to,
                                       LocalDate today, int maxPeriods) {
        LocalDate effectiveTo = to.isAfter(today) ? today : to;
        if (from.isAfter(effectiveTo)) {
            return List.of();
        }
        LocalDate last = granularity.periodStart(effectiveTo);
        List<Period> periods = new ArrayList<>();
        for (LocalDate start = granularity.periodStart(from); !start.isAfter(last);
             start = granularity.nextPeriodStart(start)) {
            if (periods.size() >= maxPeriods) {
                throw new InvalidKolQueryException("range covers more than " + maxPeriods + " periods");
            }
            periods.add(new Period(start, granularity.periodEnd(start).isBefore(today)));
        }
        return periods;
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/SegmentTrajectory.java`：

```java
package com.jiaxian.analytics.kol;

import java.util.ArrayList;
import java.util.List;

/** 纯逻辑：从按统计日升序的历史里找出标签变化的位置（变化发生在后一期）。 */
public final class SegmentTrajectory {

    private SegmentTrajectory() {
    }

    public static List<SegmentChange> changes(List<HistoryPoint> history) {
        List<SegmentChange> changes = new ArrayList<>();
        for (int i = 1; i < history.size(); i++) {
            HistoryPoint previous = history.get(i - 1);
            HistoryPoint current = history.get(i);
            if (previous.segment() != current.segment()) {
                changes.add(new SegmentChange(current.asOf(), previous.segment(), current.segment()));
            }
        }
        return changes;
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn test -Dtest='PeriodAxisTest,SegmentTrajectoryTest'`
Expected: `PeriodAxisTest` 5 个、`SegmentTrajectoryTest` 4 个，全部通过。

- [ ] **Step 5: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src
git commit -m "feat: add blogger profile records, period axis and segment trajectory

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 详情页取数仓库

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/kol/BloggerProfileRepository.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/kol/BloggerProfileRepositoryIT.java`

- [ ] **Step 1: 写失败的测试**

夹具（2019 年，员工 X；员工 Y 的行为不应串进 X 的结果）：

| 时间 | X 的活跃行为 |
|---|---|
| 2019-06-03 09:00 | POST |
| 2019-06-04 10:00 | COMMENT |
| 2019-06-04 11:00、12:00 | LOGIN ×2 |
| 2019-06-10 08:00 | LOGIN |
| 2019-06-05 09:00 | VIEW（不计） |

Y：2019-06-04 09:00 一次 LOGIN。X 的积分流水：06-03 ACTIVE 100，06-04 ACTIVE 20，06-04 PASSIVE 15，06-11 PASSIVE 5；另有 2019-05-31 23:59:59 ACTIVE 777（早于范围）和 2019-07-01 00:00:00 ACTIVE 999（晚于范围）。每周重算记录（直接 SQL 插入）：X 在 2019-06-09 为 `STAR_KOL`（名次 2，分数 5/5/5，无预警），2019-06-16 为 `CHURN_RISK`（名次 3，分数 2/5/5，预警），2019-06-23 为 `CHURN_RISK`（名次 4，R/F/M 原值 26/12/480，12 个月积分 1234，预警）并带一行预警详情（`ACTIVITY`，近期 0.0、基线 0.5、下降 1.0）。

`backend/src/test/java/com/jiaxian/analytics/kol/BloggerProfileRepositoryIT.java`：

```java
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
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest=BloggerProfileRepositoryIT`
Expected: 编译失败。

- [ ] **Step 3: 写实现**

`backend/src/main/java/com/jiaxian/analytics/kol/BloggerProfileRepository.java`：

```java
package com.jiaxian.analytics.kol;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 博主详情页取数。日期列在 A 兼容模式下是 timestamp：参数一律绑定 Timestamp，读取一律走 getTimestamp；
 * 按天取数用 date_trunc('day', …) 分组，分桶补零由 Service 在 Java 里做。
 */
@Repository
public class BloggerProfileRepository {

    private static final int MAX_EVENT_LIMIT = 100;

    public record DailyActivity(LocalDate date, String type, long count) {
    }

    public record DailyPoints(LocalDate date, String category, long points) {
    }

    /** blogger_rfm_score 的一行联同一统计日的预警详情（没有预警为 null）。 */
    public record StandingRow(int rank, Segment segment, boolean churnAlert, long rollingPoints12m,
                              Integer recencyDays, int frequency, long monetary,
                              int rScore, int fScore, int mScore, AlertDetail alert) {
    }

    private final JdbcTemplate jdbc;

    public BloggerProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EmployeeInfo> employee(long id) {
        return jdbc.query("SELECT id, name, dept, join_date FROM employee WHERE id = ?", (rs, rowNum) -> {
            Timestamp joinDate = rs.getTimestamp(4);
            return new EmployeeInfo(rs.getLong(1), rs.getString(2), rs.getString(3),
                joinDate == null ? null : joinDate.toLocalDateTime().toLocalDate());
        }, id).stream().findFirst();
    }

    public Optional<StandingRow> standing(long employeeId, LocalDate asOf) {
        return jdbc.query("SELECT r.rank_no, r.segment_label, r.churn_alert, r.rolling_points_12m, r.recency_days,"
                + " r.frequency, r.monetary, r.r_score, r.f_score, r.m_score,"
                + " a.metric, a.recent_daily, a.baseline_daily, a.drop_rate"
                + " FROM blogger_rfm_score r LEFT JOIN churn_alert a"
                + " ON a.alert_date = r.as_of_date AND a.employee_id = r.employee_id"
                + " WHERE r.as_of_date = ? AND r.employee_id = ?", (rs, rowNum) -> {
            int recency = rs.getInt(5);
            Integer recencyDays = rs.wasNull() ? null : recency;
            String metric = rs.getString(11);
            AlertDetail alert = metric == null ? null : new AlertDetail(
                ChurnSignal.Metric.valueOf(metric), rs.getDouble(12), rs.getDouble(13), rs.getDouble(14));
            return new StandingRow(rs.getInt(1), Segment.valueOf(rs.getString(2)), rs.getBoolean(3), rs.getLong(4),
                recencyDays, rs.getInt(6), rs.getLong(7), rs.getInt(8), rs.getInt(9), rs.getInt(10), alert);
        }, ts(asOf), employeeId).stream().findFirst();
    }

    /** 统计日在 [from, toInclusive] 内的每周重算结果，按统计日升序。 */
    public List<HistoryPoint> history(long employeeId, LocalDate from, LocalDate toInclusive) {
        return jdbc.query("SELECT as_of_date, rank_no, segment_label, churn_alert, r_score, f_score, m_score"
                + " FROM blogger_rfm_score WHERE employee_id = ? AND as_of_date >= ? AND as_of_date <= ?"
                + " ORDER BY as_of_date", (rs, rowNum) -> {
            Segment segment = Segment.valueOf(rs.getString(3));
            return new HistoryPoint(rs.getTimestamp(1).toLocalDateTime().toLocalDate(), rs.getInt(2), segment,
                segment.displayName(), rs.getBoolean(4), rs.getInt(5), rs.getInt(6), rs.getInt(7));
        }, employeeId, ts(from), ts(toInclusive));
    }

    /** 范围内每天每种类型（发帖/评论/登录）的次数，from、toInclusive 都包含。 */
    public List<DailyActivity> dailyActivity(long employeeId, LocalDate from, LocalDate toInclusive) {
        return jdbc.query("SELECT date_trunc('day', occur_time), action_type, COUNT(*)"
                + " FROM active_action_log WHERE employee_id = ? AND action_type IN ('POST', 'COMMENT', 'LOGIN')"
                + " AND occur_time >= ? AND occur_time < ?"
                + " GROUP BY date_trunc('day', occur_time), action_type",
            (rs, rowNum) -> new DailyActivity(rs.getTimestamp(1).toLocalDateTime().toLocalDate(),
                rs.getString(2), rs.getLong(3)),
            employeeId, ts(from), ts(toInclusive.plusDays(1)));
    }

    /** 范围内每天主动/被动积分之和，from、toInclusive 都包含。 */
    public List<DailyPoints> dailyPoints(long employeeId, LocalDate from, LocalDate toInclusive) {
        return jdbc.query("SELECT date_trunc('day', occur_time), category, SUM(points)"
                + " FROM points_ledger WHERE employee_id = ? AND occur_time >= ? AND occur_time < ?"
                + " GROUP BY date_trunc('day', occur_time), category",
            (rs, rowNum) -> new DailyPoints(rs.getTimestamp(1).toLocalDateTime().toLocalDate(),
                rs.getString(2), rs.getLong(3)),
            employeeId, ts(from), ts(toInclusive.plusDays(1)));
    }

    /** 最近的发帖/评论/登录，时间倒序（同一时间按 id 倒序）。 */
    public List<RecentEvent> recentEvents(long employeeId, int limit) {
        if (limit < 1 || limit > MAX_EVENT_LIMIT) {
            // LIMIT 以字面量写进 SQL（OpenGauss 不接受绑定参数形式的分页），所以必须先确认是安全的整数范围。
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_EVENT_LIMIT);
        }
        return jdbc.query("SELECT occur_time, action_type, ref_id FROM active_action_log"
                + " WHERE employee_id = ? AND action_type IN ('POST', 'COMMENT', 'LOGIN')"
                + " ORDER BY occur_time DESC, id DESC LIMIT " + limit, (rs, rowNum) -> {
            long refId = rs.getLong(3);
            return new RecentEvent(rs.getTimestamp(1).toLocalDateTime(), rs.getString(2),
                rs.wasNull() ? null : refId);
        }, employeeId);
    }

    private static Timestamp ts(LocalDate date) {
        return Timestamp.valueOf(date.atStartOfDay());
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn test -Dtest=BloggerProfileRepositoryIT`
Expected: `Tests run: 9, Failures: 0, Errors: 0`

- [ ] **Step 5: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src
git commit -m "feat: add blogger profile repository

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 详情页服务

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/kol/BloggerProfileService.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/kol/BloggerProfileServiceTest.java`

测试用 Mockito 替身仓库 + 固定时钟（今天 = 2026-06-10，周三）。默认范围手算：WEEK 默认 `to` = 06-10，`from` = 06-10 往前 25 周 = 2025-12-17（周三），扩展到周一 2025-12-15，到 06-08 这一周共 26 个周期；DAY 默认 `from` = 2026-05-12，共 30 个周期。

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/jiaxian/analytics/kol/BloggerProfileServiceTest.java`：

```java
package com.jiaxian.analytics.kol;

import com.jiaxian.analytics.pv.Granularity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BloggerProfileServiceTest {

    private static final long ID = 3601;
    private static final EmployeeInfo EMPLOYEE = new EmployeeInfo(ID, "员工-1", null, null);

    private final BloggerProfileRepository repository = mock(BloggerProfileRepository.class);
    private final KolRfmRepository rfmRepository = mock(KolRfmRepository.class);
    private final PlaybookService playbookService = mock(PlaybookService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-10T08:00:00Z"), ZoneOffset.UTC);
    private final BloggerProfileService service =
        new BloggerProfileService(repository, rfmRepository, playbookService, clock);

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    @BeforeEach
    void setUp() {
        when(repository.employee(ID)).thenReturn(Optional.of(EMPLOYEE));
        when(repository.recentEvents(ID, 20)).thenReturn(List.of());
        when(playbookService.lookup()).thenReturn(new PlaybookLookup(List.of(
            new PlaybookEntry(1, Segment.CHURN_RISK, PlaybookAlertStatus.ALERT, "私信关怀"))));
    }

    private static HistoryPoint point(LocalDate asOf, Segment segment) {
        return new HistoryPoint(asOf, 1, segment, segment.displayName(), segment == Segment.CHURN_RISK, 3, 3, 3);
    }

    // ---- profile ----

    @Test
    void profileWithoutAnyRebuildHasNoCurrentAndNoHistory() {
        when(rfmRepository.latestAsOf()).thenReturn(Optional.empty());
        RecentEvent event = new RecentEvent(LocalDateTime.of(2026, 3, 1, 10, 0), "LOGIN", null);
        when(repository.recentEvents(ID, 20)).thenReturn(List.of(event));

        BloggerProfile profile = service.profile(ID, 26);

        assertThat(profile.employee()).isEqualTo(EMPLOYEE);
        assertThat(profile.current()).isNull();
        assertThat(profile.history()).isEmpty();
        assertThat(profile.segmentChanges()).isEmpty();
        assertThat(profile.recentEvents()).containsExactly(event);
        verify(repository, never()).history(anyLong(), any(), any());
    }

    @Test
    void profileBuildsCurrentHistoryAndSegmentChanges() {
        LocalDate latest = d(2026, 3, 1);
        when(rfmRepository.latestAsOf()).thenReturn(Optional.of(latest));
        // weeks=3 → 最近 3 次每周重算：窗口 [latest − 14 天, latest]
        when(repository.history(ID, d(2026, 2, 15), latest)).thenReturn(List.of(
            point(d(2026, 2, 15), Segment.STAR_KOL), point(d(2026, 2, 22), Segment.STAR_KOL),
            point(latest, Segment.CHURN_RISK)));
        when(repository.standing(ID, latest)).thenReturn(Optional.of(new BloggerProfileRepository.StandingRow(
            7, Segment.CHURN_RISK, true, 5200, 26, 12, 480, 2, 5, 5,
            new AlertDetail(ChurnSignal.Metric.ACTIVITY, 0.0, 0.52, 1.0))));

        BloggerProfile profile = service.profile(ID, 3);

        CurrentStanding current = profile.current();
        assertThat(current.asOf()).isEqualTo(latest);
        assertThat(current.rank()).isEqualTo(7);
        assertThat(current.segment()).isEqualTo(Segment.CHURN_RISK);
        assertThat(current.segmentName()).isEqualTo("流失预警");
        assertThat(current.churnAlert()).isTrue();
        assertThat(current.recommendation()).isEqualTo("私信关怀");
        assertThat(current.rollingPoints12m()).isEqualTo(5200);
        assertThat(current.recencyDays()).isEqualTo(26);
        assertThat(current.alert()).isEqualTo(new AlertDetail(ChurnSignal.Metric.ACTIVITY, 0.0, 0.52, 1.0));
        assertThat(profile.history()).hasSize(3);
        assertThat(profile.segmentChanges()).containsExactly(
            new SegmentChange(latest, Segment.STAR_KOL, Segment.CHURN_RISK));
    }

    @Test
    void anEmployeeNotInTheLatestRunHasNoCurrentButKeepsTheHistory() {
        LocalDate latest = d(2026, 3, 1);
        when(rfmRepository.latestAsOf()).thenReturn(Optional.of(latest));
        when(repository.standing(ID, latest)).thenReturn(Optional.empty());
        when(repository.history(ID, d(2025, 9, 7), latest)).thenReturn(List.of(point(d(2025, 12, 7), Segment.STEADY)));

        BloggerProfile profile = service.profile(ID, 26);

        assertThat(profile.current()).isNull();
        assertThat(profile.history()).hasSize(1);
    }

    @Test
    void profileRejectsBadWeeksAndUnknownEmployees() {
        assertThatThrownBy(() -> service.profile(ID, 0))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("weeks");
        assertThatThrownBy(() -> service.profile(ID, 105))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("weeks");

        when(repository.employee(-1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.profile(-1L, 26)).isInstanceOf(EmployeeNotFoundException.class);
    }

    // ---- activity ----

    @Test
    void activityBucketsDailyRowsIntoWeeks() {
        // from=06-03 扩展到周一 06-01；查库范围是 [06-01, 06-09]
        when(repository.dailyActivity(ID, d(2026, 6, 1), d(2026, 6, 9))).thenReturn(List.of(
            new BloggerProfileRepository.DailyActivity(d(2026, 6, 2), "POST", 2),
            new BloggerProfileRepository.DailyActivity(d(2026, 6, 3), "LOGIN", 3),
            new BloggerProfileRepository.DailyActivity(d(2026, 6, 4), "COMMENT", 1),
            new BloggerProfileRepository.DailyActivity(d(2026, 6, 9), "LOGIN", 4)));

        ActivitySeries series = service.activity(ID, Granularity.WEEK, d(2026, 6, 3), d(2026, 6, 9));

        assertThat(series.granularity()).isEqualTo(Granularity.WEEK);
        assertThat(series.from()).isEqualTo(d(2026, 6, 3));
        assertThat(series.to()).isEqualTo(d(2026, 6, 9));
        assertThat(series.points()).containsExactly(
            new ActivityPoint(d(2026, 6, 1), 2, 1, 3, true),
            new ActivityPoint(d(2026, 6, 8), 0, 0, 4, false));
    }

    @Test
    void activityDefaultsToTwentySixWeeksEndingToday() {
        when(repository.dailyActivity(ID, d(2025, 12, 15), d(2026, 6, 10))).thenReturn(List.of());

        ActivitySeries series = service.activity(ID, null, null, null);

        assertThat(series.granularity()).isEqualTo(Granularity.WEEK);
        assertThat(series.from()).isEqualTo(d(2025, 12, 17));
        assertThat(series.to()).isEqualTo(d(2026, 6, 10));
        assertThat(series.points()).hasSize(26);
        assertThat(series.points()).allSatisfy(p -> {
            assertThat(p.posts()).isZero();
            assertThat(p.logins()).isZero();
        });
        assertThat(series.points().get(25).complete()).isFalse();
    }

    @Test
    void dailyActivityDefaultsToThirtyDays() {
        when(repository.dailyActivity(ID, d(2026, 5, 12), d(2026, 6, 10))).thenReturn(List.of());

        ActivitySeries series = service.activity(ID, Granularity.DAY, null, null);

        assertThat(series.from()).isEqualTo(d(2026, 5, 12));
        assertThat(series.points()).hasSize(30);
    }

    @Test
    void aRangeInTheFutureHasNoPointsAndDoesNotQuery() {
        ActivitySeries series = service.activity(ID, Granularity.MONTH, d(2026, 7, 1), d(2026, 7, 31));

        assertThat(series.points()).isEmpty();
        verify(repository, never()).dailyActivity(anyLong(), any(), any());
    }

    @Test
    void activityRejectsBadRanges() {
        assertThatThrownBy(() -> service.activity(ID, Granularity.DAY, d(2026, 6, 5), d(2026, 6, 1)))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("from");
        assertThatThrownBy(() -> service.activity(ID, Granularity.DAY, d(2025, 1, 1), d(2026, 6, 10)))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("400");

        when(repository.employee(-1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.activity(-1L, Granularity.WEEK, null, null))
            .isInstanceOf(EmployeeNotFoundException.class);
    }

    // ---- points-trend ----

    @Test
    void pointsTrendSplitsActiveAndPassivePerPeriod() {
        when(repository.dailyPoints(ID, d(2026, 6, 1), d(2026, 6, 9))).thenReturn(List.of(
            new BloggerProfileRepository.DailyPoints(d(2026, 6, 2), "ACTIVE", 100),
            new BloggerProfileRepository.DailyPoints(d(2026, 6, 3), "PASSIVE", 15),
            new BloggerProfileRepository.DailyPoints(d(2026, 6, 9), "ACTIVE", 20)));

        PointsTrend trend = service.pointsTrend(ID, Granularity.WEEK, d(2026, 6, 3), d(2026, 6, 9));

        assertThat(trend.points()).containsExactly(
            new PointsTrendPoint(d(2026, 6, 1), 100, 15, 115, true),
            new PointsTrendPoint(d(2026, 6, 8), 20, 0, 20, false));
    }

    @Test
    void pointsTrendDefaultsToTwelveMonthsForMonthGranularity() {
        // to=06-10，from = 往前 11 个月 = 2025-07-10 → 扩展到 2025-07-01
        when(repository.dailyPoints(ID, d(2025, 7, 1), d(2026, 6, 10))).thenReturn(List.of());

        PointsTrend trend = service.pointsTrend(ID, Granularity.MONTH, null, null);

        assertThat(trend.points()).hasSize(12);
        assertThat(trend.points().get(0).periodStart()).isEqualTo(d(2025, 7, 1));
        assertThat(trend.points().get(11).complete()).isFalse();
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest=BloggerProfileServiceTest`
Expected: 编译失败。

- [ ] **Step 3: 写实现**

`backend/src/main/java/com/jiaxian/analytics/kol/BloggerProfileService.java`：

```java
package com.jiaxian.analytics.kol;

import com.jiaxian.analytics.pv.Granularity;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 博主详情页：校验参数、解析默认范围、组装 profile，并把按天的行聚成连续周期（补零）。 */
@Service
public class BloggerProfileService {

    static final int MAX_WEEKS = 104;
    static final int MAX_PERIODS = 400;
    static final int RECENT_EVENT_LIMIT = 20;

    private record Range(Granularity granularity, LocalDate from, LocalDate to, LocalDate fetchTo,
                         List<PeriodAxis.Period> periods) {
    }

    private final BloggerProfileRepository repository;
    private final KolRfmRepository rfmRepository;
    private final PlaybookService playbookService;
    private final Clock clock;

    public BloggerProfileService(BloggerProfileRepository repository, KolRfmRepository rfmRepository,
                                 PlaybookService playbookService, Clock clock) {
        this.repository = repository;
        this.rfmRepository = rfmRepository;
        this.playbookService = playbookService;
        this.clock = clock;
    }

    public BloggerProfile profile(long employeeId, int weeks) {
        if (weeks < 1 || weeks > MAX_WEEKS) {
            throw new InvalidKolQueryException("weeks must be between 1 and " + MAX_WEEKS);
        }
        EmployeeInfo employee = requireEmployee(employeeId);
        List<RecentEvent> recentEvents = repository.recentEvents(employeeId, RECENT_EVENT_LIMIT);

        Optional<LocalDate> latest = rfmRepository.latestAsOf();
        if (latest.isEmpty()) {
            return new BloggerProfile(employee, null, List.of(), List.of(), recentEvents);
        }
        LocalDate asOf = latest.get();
        // 窗口覆盖最近 weeks 次每周重算：[asOf − 7×(weeks−1) 天, asOf]
        List<HistoryPoint> history = repository.history(employeeId, asOf.minusDays(7L * (weeks - 1)), asOf);
        PlaybookLookup lookup = playbookService.lookup();
        CurrentStanding current = repository.standing(employeeId, asOf)
            .map(row -> new CurrentStanding(asOf, row.rank(), row.segment(), row.segment().displayName(),
                row.churnAlert(), lookup.find(row.segment(), row.churnAlert()), row.rollingPoints12m(),
                row.recencyDays(), row.frequency(), row.monetary(), row.rScore(), row.fScore(), row.mScore(),
                row.alert()))
            .orElse(null);
        return new BloggerProfile(employee, current, history, SegmentTrajectory.changes(history), recentEvents);
    }

    public ActivitySeries activity(long employeeId, Granularity granularity, LocalDate from, LocalDate to) {
        Range range = resolve(granularity, from, to);
        requireEmployee(employeeId);

        Map<LocalDate, long[]> sums = new HashMap<>();
        if (!range.periods().isEmpty()) {
            for (BloggerProfileRepository.DailyActivity row : repository.dailyActivity(
                    employeeId, range.periods().get(0).start(), range.fetchTo())) {
                long[] counts = sums.computeIfAbsent(range.granularity().periodStart(row.date()), k -> new long[3]);
                switch (row.type()) {
                    case "POST" -> counts[0] += row.count();
                    case "COMMENT" -> counts[1] += row.count();
                    case "LOGIN" -> counts[2] += row.count();
                    default -> {
                    }
                }
            }
        }
        List<ActivityPoint> points = range.periods().stream().map(period -> {
            long[] counts = sums.getOrDefault(period.start(), new long[3]);
            return new ActivityPoint(period.start(), counts[0], counts[1], counts[2], period.complete());
        }).toList();
        return new ActivitySeries(range.granularity(), range.from(), range.to(), points);
    }

    public PointsTrend pointsTrend(long employeeId, Granularity granularity, LocalDate from, LocalDate to) {
        Range range = resolve(granularity, from, to);
        requireEmployee(employeeId);

        Map<LocalDate, long[]> sums = new HashMap<>();
        if (!range.periods().isEmpty()) {
            for (BloggerProfileRepository.DailyPoints row : repository.dailyPoints(
                    employeeId, range.periods().get(0).start(), range.fetchTo())) {
                long[] points = sums.computeIfAbsent(range.granularity().periodStart(row.date()), k -> new long[2]);
                if ("ACTIVE".equals(row.category())) {
                    points[0] += row.points();
                } else if ("PASSIVE".equals(row.category())) {
                    points[1] += row.points();
                }
            }
        }
        List<PointsTrendPoint> points = range.periods().stream().map(period -> {
            long[] sum = sums.getOrDefault(period.start(), new long[2]);
            return new PointsTrendPoint(period.start(), sum[0], sum[1], sum[0] + sum[1], period.complete());
        }).toList();
        return new PointsTrend(range.granularity(), range.from(), range.to(), points);
    }

    private EmployeeInfo requireEmployee(long employeeId) {
        return repository.employee(employeeId).orElseThrow(() -> new EmployeeNotFoundException(employeeId));
    }

    /** 解析默认值并校验：granularity 默认 WEEK，to 默认今天，from 默认往前推一个粒度相关的跨度。 */
    private Range resolve(Granularity granularity, LocalDate from, LocalDate to) {
        Granularity effectiveGranularity = granularity == null ? Granularity.WEEK : granularity;
        LocalDate today = LocalDate.now(clock);
        LocalDate effectiveTo = to == null ? today : to;
        LocalDate effectiveFrom = from != null ? from : defaultFrom(effectiveGranularity, effectiveTo);
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new InvalidKolQueryException("from must not be after to");
        }
        List<PeriodAxis.Period> periods =
            PeriodAxis.periods(effectiveGranularity, effectiveFrom, effectiveTo, today, MAX_PERIODS);
        LocalDate fetchTo = effectiveTo.isAfter(today) ? today : effectiveTo;
        return new Range(effectiveGranularity, effectiveFrom, effectiveTo, fetchTo, periods);
    }

    /** DAY 30 天、WEEK 26 周、MONTH 12 个月、YEAR 5 年（含 to 所在的那一个周期）。 */
    private static LocalDate defaultFrom(Granularity granularity, LocalDate to) {
        return switch (granularity) {
            case DAY -> to.minusDays(29);
            case WEEK -> to.minusWeeks(25);
            case MONTH -> to.minusMonths(11);
            case YEAR -> to.minusYears(4);
        };
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn test -Dtest=BloggerProfileServiceTest`
Expected: `Tests run: 12, Failures: 0, Errors: 0`

- [ ] **Step 5: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src
git commit -m "feat: add blogger profile service

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: HTTP 接口与错误映射

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/kol/BloggerProfileController.java`
- Modify: `backend/src/main/java/com/jiaxian/analytics/kol/KolExceptionHandler.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/kol/BloggerProfileControllerIT.java`

- [ ] **Step 1: 写测试**（HTTP；用临时创建、用完即删的员工验证响应结构，数据同 Task 2 的 X，2019 年）

`backend/src/test/java/com/jiaxian/analytics/kol/BloggerProfileControllerIT.java`：

```java
package com.jiaxian.analytics.kol;

import com.fasterxml.jackson.databind.JsonNode;
import com.jiaxian.analytics.domain.ActiveActionType;
import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.domain.PointsCategory;
import com.jiaxian.analytics.repository.ActiveActionLogRepository;
import com.jiaxian.analytics.repository.EmployeeRepository;
import com.jiaxian.analytics.repository.PointsLedgerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BloggerProfileControllerIT {

    private static final long MISSING = 999_999_999L;

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ActiveActionLogRepository activeActionLogRepository;
    @Autowired private PointsLedgerRepository pointsLedgerRepository;

    private Employee x;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static LocalDateTime t(int month, int day, int hour) {
        return LocalDateTime.of(2019, month, day, hour, 0);
    }

    /** 这个测试走真实 HTTP，不能靠事务回滚：自己创建员工和流水，结束时自己删。 */
    @BeforeEach
    void setUp() {
        KolTestFixtures f = new KolTestFixtures(employeeRepository, activeActionLogRepository, pointsLedgerRepository);
        x = f.employee("KOL测试员工HTTP");
        f.action(x, ActiveActionType.POST, t(6, 3, 9));
        f.action(x, ActiveActionType.COMMENT, t(6, 4, 10));
        f.action(x, ActiveActionType.LOGIN, t(6, 4, 11));
        f.action(x, ActiveActionType.LOGIN, t(6, 4, 12));
        f.action(x, ActiveActionType.LOGIN, t(6, 10, 8));
        f.ledger(x, PointsCategory.ACTIVE, 100, t(6, 3, 12));
        f.ledger(x, PointsCategory.ACTIVE, 20, t(6, 4, 12));
        f.ledger(x, PointsCategory.PASSIVE, 15, t(6, 4, 13));
        f.ledger(x, PointsCategory.PASSIVE, 5, t(6, 11, 12));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM active_action_log WHERE employee_id = ?", x.getId());
        jdbc.update("DELETE FROM points_ledger WHERE employee_id = ?", x.getId());
        jdbc.update("DELETE FROM employee WHERE id = ?", x.getId());
    }

    private void assertStatus(String path, int status, String messagePart) {
        ResponseEntity<String> response = rest.getForEntity(url(path), String.class);
        assertThat(response.getStatusCode().value()).as(path).isEqualTo(status);
        assertThat(response.getBody()).as(path).contains(messagePart);
    }

    @Test
    void profileOfAnEmployeeOutsideTheRankingHasNoCurrentStanding() {
        ResponseEntity<JsonNode> response = rest.getForEntity(url("/api/bloggers/" + x.getId() + "/profile"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body.get("employee").get("id").asLong()).isEqualTo(x.getId());
        assertThat(body.get("employee").get("name").asText()).isEqualTo("KOL测试员工HTTP");
        assertThat(body.get("current").isNull()).isTrue();
        assertThat(body.get("history")).isEmpty();
        assertThat(body.get("segmentChanges")).isEmpty();
        assertThat(body.get("recentEvents")).hasSize(5);
        assertThat(body.get("recentEvents").get(0).get("type").asText()).isEqualTo("LOGIN");
        assertThat(body.get("recentEvents").get(0).get("time").asText()).startsWith("2019-06-10T08:00");
    }

    @Test
    void activityReturnsZeroFilledWeeklyCounts() {
        ResponseEntity<JsonNode> response = rest.getForEntity(
            url("/api/bloggers/" + x.getId() + "/activity?granularity=WEEK&from=2019-06-01&to=2019-06-30"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body.get("granularity").asText()).isEqualTo("WEEK");
        assertThat(body.get("from").asText()).isEqualTo("2019-06-01");
        assertThat(body.get("to").asText()).isEqualTo("2019-06-30");
        // 06-01 是周六，向前扩展到周一 05-27；共 5 周：05-27、06-03、06-10、06-17、06-24
        assertThat(body.get("points")).hasSize(5);
        assertThat(body.get("points").get(0).get("periodStart").asText()).isEqualTo("2019-05-27");
        assertThat(body.get("points").get(0).get("logins").asLong()).isZero();
        JsonNode week = body.get("points").get(1);
        assertThat(week.get("periodStart").asText()).isEqualTo("2019-06-03");
        assertThat(week.get("posts").asLong()).isEqualTo(1);
        assertThat(week.get("comments").asLong()).isEqualTo(1);
        assertThat(week.get("logins").asLong()).isEqualTo(2);
        assertThat(week.get("complete").asBoolean()).isTrue();
        assertThat(body.get("points").get(2).get("logins").asLong()).isEqualTo(1);
    }

    @Test
    void pointsTrendSplitsActiveAndPassive() {
        ResponseEntity<JsonNode> response = rest.getForEntity(
            url("/api/bloggers/" + x.getId() + "/points-trend?granularity=MONTH&from=2019-06-01&to=2019-06-30"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode points = response.getBody().get("points");
        assertThat(points).hasSize(1);
        assertThat(points.get(0).get("periodStart").asText()).isEqualTo("2019-06-01");
        assertThat(points.get(0).get("activePoints").asLong()).isEqualTo(120);
        assertThat(points.get(0).get("passivePoints").asLong()).isEqualTo(20);
        assertThat(points.get(0).get("total").asLong()).isEqualTo(140);
    }

    @Test
    void unknownEmployeesAreNotFound() {
        assertStatus("/api/bloggers/" + MISSING + "/profile", 404, "does not exist");
        assertStatus("/api/bloggers/" + MISSING + "/activity", 404, "does not exist");
        assertStatus("/api/bloggers/" + MISSING + "/points-trend", 404, "does not exist");
    }

    @Test
    void badParametersAreRejected() {
        long id = x.getId();
        assertStatus("/api/bloggers/abc/profile", 400, "id");
        assertStatus("/api/bloggers/" + id + "/profile?weeks=0", 400, "weeks");
        assertStatus("/api/bloggers/" + id + "/profile?weeks=105", 400, "weeks");
        assertStatus("/api/bloggers/" + id + "/activity?granularity=HOURLY", 400, "granularity");
        assertStatus("/api/bloggers/" + id + "/activity?from=2019-6-1", 400, "from");
        assertStatus("/api/bloggers/" + id + "/activity?from=2019-06-05&to=2019-06-01", 400, "from must not be after to");
        assertStatus("/api/bloggers/" + id + "/activity?granularity=DAY&from=2010-01-01&to=2019-06-01", 400, "400 periods");
        assertStatus("/api/bloggers/" + id + "/points-trend?granularity=DAY&from=2010-01-01&to=2019-06-01", 400, "400 periods");
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest=BloggerProfileControllerIT`
Expected: 测试失败（接口还不存在，返回 404 或 500）。

- [ ] **Step 3: 写实现**

`backend/src/main/java/com/jiaxian/analytics/kol/BloggerProfileController.java`：

```java
package com.jiaxian.analytics.kol;

import com.jiaxian.analytics.pv.Granularity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 个人博主详情页：基本信息 + 分层 + 历史轨迹、活跃时间轴、积分构成趋势。员工不存在返回 404。 */
@RestController
@RequestMapping("/api/bloggers/{id}")
public class BloggerProfileController {

    private final BloggerProfileService service;

    public BloggerProfileController(BloggerProfileService service) {
        this.service = service;
    }

    /** weeks：历史轨迹覆盖最近几次每周重算，默认 26，范围 1~104。 */
    @GetMapping("/profile")
    public BloggerProfile profile(@PathVariable long id, @RequestParam(defaultValue = "26") int weeks) {
        return service.profile(id, weeks);
    }

    /** 活跃时间轴：各周期的发帖/评论/登录次数。granularity 默认 WEEK，to 默认今天，from 默认按粒度往前推。 */
    @GetMapping("/activity")
    public ActivitySeries activity(
        @PathVariable long id,
        @RequestParam(required = false) Granularity granularity,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.activity(id, granularity, from, to);
    }

    /** 积分构成趋势：各周期的主动分、被动分（堆叠图）。参数规则同 activity。 */
    @GetMapping("/points-trend")
    public PointsTrend pointsTrend(
        @PathVariable long id,
        @RequestParam(required = false) Granularity granularity,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.pointsTrend(id, granularity, from, to);
    }
}
```

修改 `backend/src/main/java/com/jiaxian/analytics/kol/KolExceptionHandler.java`：

1. 把类上的注解改为

```java
@RestControllerAdvice(assignableTypes = {BloggerController.class, KolAdminController.class,
    PlaybookController.class, BloggerProfileController.class})
```

2. 在 `notFound(PlaybookNotFoundException e)` 方法后面加一个处理员工不存在的方法：

```java
    @ExceptionHandler(EmployeeNotFoundException.class)
    public ResponseEntity<String> employeeNotFound(EmployeeNotFoundException e) {
        return text(HttpStatus.NOT_FOUND, e.getMessage());
    }
```

同时把类上方的注释 `只作用于 KOL 接口：参数问题 400，文案不存在 404，文案键冲突 409` 改成 `只作用于 KOL 接口：参数问题 400，文案或员工不存在 404，文案键冲突 409`。

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn test -Dtest=BloggerProfileControllerIT`
Expected: `Tests run: 5, Failures: 0, Errors: 0`

排查：
- `/api/bloggers/abc/profile` 的 400 响应里没有 `id`：说明类型转换异常没走新处理器，确认 `assignableTypes` 已包含 `BloggerProfileController`。
- 路径冲突：`BloggerController` 的 `/api/bloggers/top1000` 与新控制器的 `/api/bloggers/{id}/…` 前缀不同层级，不会冲突；如果出现 `Ambiguous mapping`，检查是不是把 `@RequestMapping` 写错。

- [ ] **Step 5: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src
git commit -m "feat: add blogger profile, activity and points-trend endpoints

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 端到端一致性测试

**Files:**
- Test: `backend/src/test/java/com/jiaxian/analytics/kol/BloggerProfileEndToEndIT.java`

开画像回填 → 补 14 周重算 → 取一个流失者的详情，并验证活跃次数合计、积分合计与数据库一致。

- [ ] **Step 1: 写测试**

`backend/src/test/java/com/jiaxian/analytics/kol/BloggerProfileEndToEndIT.java`：

```java
package com.jiaxian.analytics.kol;

import com.fasterxml.jackson.databind.JsonNode;
import com.jiaxian.analytics.simulator.BloggerPersona;
import com.jiaxian.analytics.simulator.HistoricalBackfillService;
import com.jiaxian.analytics.simulator.PersonaAssigner;
import com.jiaxian.analytics.simulator.SimulationConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 OpenGauss + 真实 HTTP：开画像回填 → 补 14 周重算 → 流失者的详情与数据库一致。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BloggerProfileEndToEndIT {

    private static final long SEED = 21;
    private static final int EMPLOYEES = 50;
    private static final LocalDate END = LocalDate.of(2026, 3, 1);

    @Autowired private HistoricalBackfillService backfillService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void backfill() {
        backfillService.runBackfill(SimulationConfig.builder()
            .batchId("profile-e2e").employeeCount(EMPLOYEES).accountCount(20).teamAccountRatio(0.25).contentCount(100)
            .historyStart(LocalDate.of(2025, 1, 1)).historyEnd(END).randomSeed(SEED).personas(true).build());
    }

    private JsonNode get(String path) {
        ResponseEntity<JsonNode> response = rest.getForEntity(url(path), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    @Test
    void aChurningBloggersProfileMatchesTheDatabase() {
        backfill();
        ResponseEntity<JsonNode> rebuild = rest.postForEntity(
            url("/api/admin/analytics/kol/rebuild?asOf=" + END + "&weeks=14"), null, JsonNode.class);
        assertThat(rebuild.getStatusCode().value()).isEqualTo(200);

        // 找一个"流失者"画像的员工（画像是 (员工数, 种子) 的纯函数，按员工 id 升序对应）
        List<Long> employeeIds = jdbc.queryForList("SELECT id FROM employee ORDER BY id", Long.class);
        List<BloggerPersona> personas = PersonaAssigner.assign(EMPLOYEES, SEED);
        long churnerId = -1;
        for (int i = 0; i < EMPLOYEES && churnerId < 0; i++) {
            if (personas.get(i) == BloggerPersona.CHURNING) {
                churnerId = employeeIds.get(i);
            }
        }
        assertThat(churnerId).isPositive();

        JsonNode profile = get("/api/bloggers/" + churnerId + "/profile?weeks=14");

        // 当前：流失预警，带预警详情和建议文案
        JsonNode current = profile.get("current");
        assertThat(current.isNull()).isFalse();
        assertThat(current.get("asOf").asText()).isEqualTo(END.toString());
        assertThat(current.get("segment").asText()).isEqualTo("CHURN_RISK");
        assertThat(current.get("churnAlert").asBoolean()).isTrue();
        assertThat(current.get("alert").get("dropRate").asDouble()).isGreaterThan(0.5);
        assertThat(current.get("recommendation").asText()).contains("私信关怀");

        // 历史：升序，最后一期就是当前；标签变化点里有"变成流失预警"
        JsonNode history = profile.get("history");
        assertThat(history.size()).isBetween(2, 14);
        String previousAsOf = "";
        for (JsonNode point : history) {
            assertThat(point.get("asOf").asText()).isGreaterThan(previousAsOf);
            previousAsOf = point.get("asOf").asText();
        }
        assertThat(history.get(history.size() - 1).get("asOf").asText()).isEqualTo(END.toString());
        boolean becameChurnRisk = false;
        for (JsonNode change : profile.get("segmentChanges")) {
            becameChurnRisk |= change.get("to").asText().equals("CHURN_RISK");
        }
        assertThat(becameChurnRisk).as("history=" + history).isTrue();

        // 最近事件：20 条，时间倒序
        JsonNode events = profile.get("recentEvents");
        assertThat(events).hasSize(20);
        LocalDateTime previous = null;
        for (JsonNode event : events) {
            LocalDateTime time = LocalDateTime.parse(event.get("time").asText());
            if (previous != null) {
                assertThat(time).isBeforeOrEqualTo(previous);
            }
            previous = time;
        }

        // 活跃时间轴：各周期合计 = 该员工在 active_action_log 里的发帖/评论/登录总行数（周、月两种粒度）
        long activityRows = jdbc.queryForObject("SELECT COUNT(*) FROM active_action_log WHERE employee_id = ?"
            + " AND action_type IN ('POST', 'COMMENT', 'LOGIN')", Long.class, churnerId);
        assertThat(activityRows).isGreaterThan(100);
        for (String granularity : new String[] {"WEEK", "MONTH"}) {
            JsonNode activity = get("/api/bloggers/" + churnerId + "/activity?granularity=" + granularity
                + "&from=2025-01-01&to=2026-12-31");
            long total = 0;
            for (JsonNode point : activity.get("points")) {
                total += point.get("posts").asLong() + point.get("comments").asLong() + point.get("logins").asLong();
            }
            assertThat(total).as(granularity).isEqualTo(activityRows);
        }

        // 积分构成趋势：合计及主动/被动拆分 = points_ledger（周、月两种粒度）
        long activeExpected = ledgerSum(churnerId, "ACTIVE");
        long passiveExpected = ledgerSum(churnerId, "PASSIVE");
        for (String granularity : new String[] {"WEEK", "MONTH"}) {
            JsonNode trend = get("/api/bloggers/" + churnerId + "/points-trend?granularity=" + granularity
                + "&from=2025-01-01&to=2026-12-31");
            long active = 0;
            long passive = 0;
            for (JsonNode point : trend.get("points")) {
                active += point.get("activePoints").asLong();
                passive += point.get("passivePoints").asLong();
                assertThat(point.get("total").asLong())
                    .isEqualTo(point.get("activePoints").asLong() + point.get("passivePoints").asLong());
            }
            assertThat(active).as(granularity + " active").isEqualTo(activeExpected);
            assertThat(passive).as(granularity + " passive").isEqualTo(passiveExpected);
        }

        // 再次回填会 deleteAll() 全部员工：每周结果必须随员工级联清空
        backfill();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blogger_rfm_score", Long.class)).isZero();
    }

    private long ledgerSum(long employeeId, String category) {
        Long sum = jdbc.queryForObject(
            "SELECT COALESCE(SUM(points), 0) FROM points_ledger WHERE employee_id = ? AND category = ?",
            Long.class, employeeId, category);
        return sum == null ? 0 : sum;
    }
}
```

- [ ] **Step 2: 运行**

Run: `cd backend && mvn test -Dtest=BloggerProfileEndToEndIT`
Expected: `Tests run: 1, Failures: 0, Errors: 0`

失败时的排查顺序：
1. `becameChurnRisk` 为假：失败信息里带 `history=…`，看这个员工每一期的标签；如果第一期就已经是 `CHURN_RISK`（冷启动回退），把 `weeks` 加大到 14 不变、或改成断言"历史里出现过 `CHURN_RISK`"。
2. 活跃/积分合计对不上：确认没有晚于今天的流水（回填不会产生），以及 `to=2026-12-31` 被正确截断到今天。
3. `recentEvents` 少于 20 条：这个员工的活跃行为不足 20 条——换一个画像或种子，或把断言改成 `isEqualTo(Math.min(20, activityRows))`。

- [ ] **Step 3: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src
git commit -m "test: add end-to-end blogger profile consistency test

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 手工验证与收尾

**Files:**
- Modify: `docs/superpowers/specs/2026-09-23-community-analytics-design.md`
- Modify: `docs/superpowers/specs/2026-10-04-blogger-profile-design.md`（状态行）

- [ ] **Step 1: 启动应用，开画像回填并补 12 周**

```bash
cd backend && mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8093
```

另开终端：

```bash
curl -s -X POST localhost:8093/api/admin/simulator/backfill -H 'Content-Type: application/json' \
  -d '{"batchId":"manual-profile","employeeCount":50,"accountCount":20,"teamAccountRatio":0.25,"contentCount":200,"historyStart":"2025-01-01","historyEnd":"2026-03-01","randomSeed":7,"personas":true}'
curl -s -X POST 'localhost:8093/api/admin/analytics/kol/rebuild?asOf=2026-03-01&weeks=12' | python3 -c "import json,sys; print(len(json.load(sys.stdin)))"
ID=$(curl -s 'localhost:8093/api/bloggers/top1000?segment=CHURN_RISK&limit=1' | python3 -c "import json,sys; print(json.load(sys.stdin)['items'][0]['employeeId'])")
echo "churner id = $ID"
curl -s "localhost:8093/api/bloggers/$ID/profile?weeks=12" | python3 -c "
import json,sys
r=json.load(sys.stdin)
print(r['employee']); print(r['current'])
print([(h['asOf'],h['segmentName'],h['rScore'],h['fScore'],h['mScore']) for h in r['history']])
print(r['segmentChanges']); print(len(r['recentEvents']), r['recentEvents'][:2])"
curl -s "localhost:8093/api/bloggers/$ID/activity?granularity=MONTH&from=2025-01-01&to=2026-03-31" | python3 -c "import json,sys; r=json.load(sys.stdin); print([(p['periodStart'],p['posts'],p['comments'],p['logins']) for p in r['points']])"
curl -s "localhost:8093/api/bloggers/$ID/points-trend?granularity=MONTH&from=2025-01-01&to=2026-03-31" | python3 -c "import json,sys; r=json.load(sys.stdin); print([(p['periodStart'],p['activePoints'],p['passivePoints']) for p in r['points']])"
curl -s -i "localhost:8093/api/bloggers/999999999/profile" | sed -n '1p;$p'
curl -s -i "localhost:8093/api/bloggers/$ID/activity?granularity=HOURLY" | sed -n '1p;$p'
```

Expected：
- `current` 是"流失预警"，带 `alert`（`ACTIVITY` 或 `POINTS`、下降比例 > 0.5）和建议文案；
- `history` 按统计日升序，能看到标签从明星 KOL（或稳定）变成流失预警，`segmentChanges` 里有对应变化点；
- 活跃时间轴的月度数据里，该员工在 `historyEnd` 前 60 天左右开始登录次数断崖式下降；
- 积分趋势里主动分在同一时间点明显下降；
- 不存在的员工返回 `HTTP/1.1 404` 和 "does not exist"，`granularity=HOURLY` 返回 `HTTP/1.1 400`。

- [ ] **Step 2: 关闭应用**

```bash
pkill -f spring-boot:run
sleep 5
lsof -i :8093 || echo "port 8093 free"
```

Expected: 输出 `port 8093 free`（若仍有 java 进程在监听，再 `pkill -f spring-boot:run` 一次并等几秒）。

- [ ] **Step 3: 同步母设计文档并更新状态**

在 `docs/superpowers/specs/2026-09-23-community-analytics-design.md` 第 6 节的"个人博主详情页"那一段（`**个人博主详情页**：…`）**之后**加一行说明：

```markdown
> 实现时的取舍（见 [博主详情页设计](2026-10-04-blogger-profile-design.md)）：详情页拆成三个接口——`GET /api/bloggers/{id}/profile`（基本信息、当前分层、RFM 历史轨迹、标签变化点、最近 20 条事件）、`/activity`（发帖/评论/登录次数的周期分布）、`/points-trend`（主动/被动积分堆叠趋势），后两者支持 DAY/WEEK/MONTH/YEAR 粒度切换；只展示已落库的每周重算历史，不对任意员工实时计算 RFM。
```

把 `docs/superpowers/specs/2026-10-04-blogger-profile-design.md` 的 `- 状态：已通过讨论确认` 改为 `- 状态：已实现（实施计划见 [个人博主详情页接口](../plans/2026-10-04-blogger-profile.md)）`。

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add docs
git commit -m "docs: record blogger profile design decisions and mark spec implemented

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

之后按 `superpowers:finishing-a-development-branch` 收尾；推送到 GitHub 只在用户要求时进行。

---

## Self-Review

**1. 设计文档覆盖：**
- 3.1 `profile`：`employee`/`current`（含预警详情和建议文案）/`history`（窗口 `[asOf − 7×(weeks−1), asOf]`）/`segmentChanges`/`recentEvents` → Task 1（记录类、`SegmentTrajectory`）、Task 2（`standing` 联预警、`history`、`recentEvents`）、Task 3（组装与 `weeks` 校验）、Task 4/5（HTTP 与端到端）。`current` 为 null 的两种情形（无重算记录 / 不在最近名单）→ Task 3 单测。
- 3.2/3.3/3.4：`activity`、`points-trend`、粒度、默认范围（DAY 30 天 / WEEK 26 周 / MONTH 12 个月 / YEAR 5 年）、周期扩展、补零、`complete`、400 周期上限、未来范围不查库 → Task 1（`PeriodAxis`）、Task 3（`resolve`/`defaultFrom`，单测里手算默认值）、Task 2（按天取数和边界）、Task 4/5。
- 第 4 节错误处理：404（员工不存在）、400（各种参数）、校验顺序"先参数后员工" → Task 3、Task 4。
- 第 5 节组件划分 → 文件结构表，全部有任务。
- 第 6 节测试策略：纯逻辑（Task 1）、真实库 IT（Task 2）、服务层单测（Task 3）、接口测试（Task 4）、端到端（Task 5）。

**2. 占位符扫描：** 无 TBD/TODO。Task 5 的"失败时排查"是具体的应对步骤，不是占位。

**3. 类型一致性：** `HistoryPoint(asOf, rank, segment, segmentName, churnAlert, rScore, fScore, mScore)` 在 Task 1 定义，Task 2 的仓库映射、`SegmentTrajectoryTest`、Task 3 的单测都按这个顺序；`BloggerProfileRepository.StandingRow(rank, segment, churnAlert, rollingPoints12m, recencyDays, frequency, monetary, rScore, fScore, mScore, alert)`、`DailyActivity(date, type, count)`、`DailyPoints(date, category, points)` 在 Task 2 定义，Task 3 使用；`PeriodAxis.periods(granularity, from, to, today, maxPeriods)` 和 `PeriodAxis.Period(start, complete)` 在 Task 1 定义，Task 3 使用；`CurrentStanding` 的字段顺序在 Task 1 定义，Task 3 构造时一致；`BloggerProfileService.profile/activity/pointsTrend` 的签名在 Task 3 定义，Task 4 的控制器按同样的参数调用；`PlaybookLookup`、`PlaybookService.lookup()`、`KolRfmRepository.latestAsOf()` 来自 Plan 5，名字一致。
