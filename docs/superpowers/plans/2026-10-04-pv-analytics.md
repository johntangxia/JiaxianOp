# PV 多维度聚合与预测 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付 PV 分析后端：按天预聚合的汇总表（定时 + 手动重算）、带同比/环比/预测的趋势接口、四个维度的 Top N 接口。

**Architecture:** 一张"内容 × 天"的汇总表 `pv_summary_daily`（`JdbcTemplate` 读写，无 JPA 实体）。频道/账号/博主/内容类型维度不预聚合，查询时 `JOIN content`（和 `content_author`）。分桶、补零、同比环比、预测写成纯 Java（`Granularity`、`PvSeriesBuilder`、`PvTrendAnalyzer`），用手算数字做单元测试；`PvAnalyticsService` 编排并持有 `Clock`。

**Tech Stack:** Spring Boot 3.2.5 (Java 17), JdbcTemplate, OpenGauss 5.0.0（A 兼容模式）, JUnit 5 + Mockito + AssertJ, Spring `@Scheduled`。

**设计文档：** `docs/superpowers/specs/2026-10-04-pv-analytics-design.md`（先读它）。

---

## 必读：约定与踩坑记录

执行前先读 `docs/superpowers/plans/2026-09-23-infra-and-data-model.md` 末尾的"执行过程中发现的修正记录"。与本计划直接相关的几条：

1. **A 兼容模式的 `DATE` 实际是 `timestamp`**。读日期一律 `rs.getTimestamp(i).toLocalDateTime().toLocalDate()`；按天截断一律 `date_trunc('day', col)`，**不要** `CAST(col AS DATE)`（会保留时间部分，已验证）；日期参数一律绑定 `java.sql.Timestamp`（`Timestamp.valueOf(date.atStartOfDay())`）。
2. **不支持 `ON CONFLICT`**；**分页不能用绑定参数**——Top N 的 `LIMIT` 必须是校验过的整数字面量。
3. **集成测试 `*IT` 连真实 OpenGauss**（`docker compose` 里的 `jiaxian-opengauss` 必须在运行；Colima 重启后先 `colima start` 并等数据库就绪）。Surefire 同时跑 `*Test`、`*Tests`、`*IT`。
4. **每个任务完成后都跑完整 `cd backend && mvn test`**，不要只跑 `-Dtest`。本计划没有改 `pom.xml`，不需要联网。
5. **测试夹具名字不能与种子数据重名**（15 个频道名、15 个积分权重动作类型）。本计划夹具统一用"PV测试…"前缀，日期统一用 2019 年（库里没有这个年份的真实数据，断言可以精确）。
6. 端口 8080 被本机一个无关的 Python 进程占用——手工启动应用时加 `--server.port=8093`，用完 `lsof -i :8093` 找到进程并 kill。
7. `HistoricalBackfillService.runBackfill()` 会先 `deleteAll()` 清空全部模拟表（包括 `content`）。所以汇总表的外键必须 `ON DELETE CASCADE`，否则汇总表有数据时再次回填会撞外键。
8. 所有 `*IT` 里对库的写入，优先用 `@Transactional`（测试结束自动回滚）。`JdbcTemplate` 和 JPA 共用同一个事务连接，JPA `save()`（IDENTITY 主键）会立即发 INSERT，所以 `JdbcTemplate` 能马上看到。只有走 HTTP 的测试（`RANDOM_PORT`）不能依赖回滚。

**与设计文档的一处细化：** 趋势接口"多取一年"实现为 `periodStart(from).minusYears(1).minusWeeks(1)`——额外的一周是为了让 WEEK 粒度（52 周前 = 364 天）的同比对齐不丢第一个点。Task 7 里实现，并在设计文档 5.2 同步这句话。

## 文件结构

新建（均在 `backend/src/main/java/com/jiaxian/analytics/pv/` 下，测试在 `backend/src/test/java/com/jiaxian/analytics/pv/` 下，包名 `com.jiaxian.analytics.pv`）：

| 文件 | 职责 |
|---|---|
| `Granularity.java` | 枚举 DAY/WEEK/MONTH/YEAR + 周期起止、下一周期、去年同期、预测窗口 |
| `DailyPv.java`、`PvBucket.java`、`PvPoint.java`、`ForecastPoint.java`、`PvTrend.java`、`PvTopEntry.java` | 数据记录（record） |
| `PvDimension.java`、`PvFilter.java` | Top N 维度枚举；过滤条件 |
| `InvalidPvQueryException.java` | 参数非法 → 400 |
| `PvSeriesBuilder.java` | 纯逻辑：分桶、补零、周期扩展 |
| `PvTrendAnalyzer.java` | 纯逻辑：环比、同比、预测 |
| `PvSummaryService.java` | 重算汇总表 |
| `PvConfig.java` | `Clock` Bean |
| `PvSummaryScheduler.java` | 定时重算最近 N 天 |
| `PvDailyQueryRepository.java` | 按过滤条件取按天 PV；Top N |
| `PvAnalyticsService.java` | 编排 + 校验 |
| `PvAnalyticsController.java`、`PvAdminController.java`、`PvExceptionHandler.java` | HTTP 接口与 400 处理 |

修改：
- `backend/src/main/resources/schema.sql`（追加一张表）
- `backend/src/test/resources/application.properties`（关掉定时任务）

测试：`GranularityTest`、`PvSeriesBuilderTest`、`PvTrendAnalyzerTest`、`PvSummaryServiceIT`、`PvSummarySchedulerTest`、`PvDailyQueryRepositoryIT`、`PvAnalyticsServiceTest`、`PvAnalyticsControllerIT`、`PvAnalyticsEndToEndIT`，以及测试夹具 `PvTestFixtures`。

---

### Task 1: 粒度枚举与数据记录

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/pv/Granularity.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/pv/DailyPv.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/pv/PvBucket.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/pv/PvPoint.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/pv/ForecastPoint.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/pv/PvTrend.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/pv/PvTopEntry.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/pv/PvDimension.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/pv/PvFilter.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/pv/InvalidPvQueryException.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/pv/GranularityTest.java`

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/jiaxian/analytics/pv/GranularityTest.java`：

```java
package com.jiaxian.analytics.pv;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class GranularityTest {

    @Test
    void weekStartsOnMonday() {
        // 2026-03-04 是周三，2026-03-02 是周一，2026-03-08 是周日
        assertThat(Granularity.WEEK.periodStart(LocalDate.of(2026, 3, 4))).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(Granularity.WEEK.periodStart(LocalDate.of(2026, 3, 2))).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(Granularity.WEEK.periodStart(LocalDate.of(2026, 3, 8))).isEqualTo(LocalDate.of(2026, 3, 2));
    }

    @Test
    void dayMonthAndYearStarts() {
        LocalDate date = LocalDate.of(2026, 3, 4);
        assertThat(Granularity.DAY.periodStart(date)).isEqualTo(date);
        assertThat(Granularity.MONTH.periodStart(date)).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(Granularity.YEAR.periodStart(date)).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void periodEnds() {
        assertThat(Granularity.DAY.periodEnd(LocalDate.of(2026, 3, 4))).isEqualTo(LocalDate.of(2026, 3, 4));
        assertThat(Granularity.WEEK.periodEnd(LocalDate.of(2026, 3, 2))).isEqualTo(LocalDate.of(2026, 3, 8));
        assertThat(Granularity.MONTH.periodEnd(LocalDate.of(2026, 2, 1))).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(Granularity.MONTH.periodEnd(LocalDate.of(2024, 2, 1))).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(Granularity.YEAR.periodEnd(LocalDate.of(2026, 1, 1))).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void nextPeriodStart() {
        assertThat(Granularity.DAY.nextPeriodStart(LocalDate.of(2026, 3, 31))).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(Granularity.WEEK.nextPeriodStart(LocalDate.of(2026, 3, 2))).isEqualTo(LocalDate.of(2026, 3, 9));
        assertThat(Granularity.MONTH.nextPeriodStart(LocalDate.of(2026, 12, 1))).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(Granularity.YEAR.nextPeriodStart(LocalDate.of(2026, 1, 1))).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void sameLastYear() {
        // 2 月 29 日回到去年 2 月 28 日
        assertThat(Granularity.DAY.sameLastYear(LocalDate.of(2024, 2, 29))).isEqualTo(LocalDate.of(2023, 2, 28));
        // 52 周 = 364 天：2026-03-02 往前 364 天是 2025-03-03（同样是周一）
        assertThat(Granularity.WEEK.sameLastYear(LocalDate.of(2026, 3, 2))).isEqualTo(LocalDate.of(2025, 3, 3));
        assertThat(Granularity.MONTH.sameLastYear(LocalDate.of(2026, 3, 1))).isEqualTo(LocalDate.of(2025, 3, 1));
        assertThat(Granularity.YEAR.sameLastYear(LocalDate.of(2026, 1, 1))).isEqualTo(LocalDate.of(2025, 1, 1));
    }

    @Test
    void forecastWindows() {
        assertThat(Granularity.DAY.forecastWindow()).isEqualTo(7);
        assertThat(Granularity.WEEK.forecastWindow()).isEqualTo(4);
        assertThat(Granularity.MONTH.forecastWindow()).isEqualTo(3);
        assertThat(Granularity.YEAR.forecastWindow()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest=GranularityTest`
Expected: 编译失败（`Granularity` 不存在）。

- [ ] **Step 3: 写实现**

`Granularity.java`：

```java
package com.jiaxian.analytics.pv;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/** 趋势分析的时间粒度。周一为一周起点（ISO），月取 1 号，年取 1 月 1 日。 */
public enum Granularity {
    DAY(7), WEEK(4), MONTH(3), YEAR(2);

    private final int forecastWindow;

    Granularity(int forecastWindow) {
        this.forecastWindow = forecastWindow;
    }

    /** 预测用的移动平均窗口（以本粒度的周期个数计）。 */
    public int forecastWindow() {
        return forecastWindow;
    }

    /** date 所在周期的第一天。 */
    public LocalDate periodStart(LocalDate date) {
        return switch (this) {
            case DAY -> date;
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
            case YEAR -> date.withDayOfYear(1);
        };
    }

    /** 以 periodStart 开头的周期的最后一天。 */
    public LocalDate periodEnd(LocalDate periodStart) {
        return switch (this) {
            case DAY -> periodStart;
            case WEEK -> periodStart.plusDays(6);
            case MONTH -> periodStart.with(TemporalAdjusters.lastDayOfMonth());
            case YEAR -> periodStart.with(TemporalAdjusters.lastDayOfYear());
        };
    }

    public LocalDate nextPeriodStart(LocalDate periodStart) {
        return periodEnd(periodStart).plusDays(1);
    }

    /** 去年同期的周期起点：DAY/YEAR 取一年前，WEEK 取 52 周前，MONTH 取 12 个月前。 */
    public LocalDate sameLastYear(LocalDate periodStart) {
        return switch (this) {
            case DAY, YEAR -> periodStart.minusYears(1);
            case WEEK -> periodStart.minusWeeks(52);
            case MONTH -> periodStart.minusMonths(12);
        };
    }
}
```

`DailyPv.java`：

```java
package com.jiaxian.analytics.pv;

import java.time.LocalDate;

/** 某一天的 PV（来自汇总表）。 */
public record DailyPv(LocalDate date, long pv) {
}
```

`PvBucket.java`：

```java
package com.jiaxian.analytics.pv;

import java.time.LocalDate;

/** 一个周期的 PV。complete=false 表示周期还没结束（最后一天不早于今天）。 */
public record PvBucket(LocalDate periodStart, long pv, boolean complete) {
}
```

`PvPoint.java`：

```java
package com.jiaxian.analytics.pv;

import java.time.LocalDate;

/** 趋势接口返回的一个点。momRate/yoyRate 为小数（0.05 即 +5%），无法计算时为 null。 */
public record PvPoint(LocalDate periodStart, long pv, Double momRate, Double yoyRate, boolean complete) {
}
```

`ForecastPoint.java`：

```java
package com.jiaxian.analytics.pv;

import java.time.LocalDate;

public record ForecastPoint(LocalDate periodStart, double value, double lower, double upper) {
}
```

`PvTrend.java`：

```java
package com.jiaxian.analytics.pv;

import java.time.LocalDate;
import java.util.List;

public record PvTrend(Granularity granularity, LocalDate from, LocalDate to,
                      List<PvPoint> points, List<ForecastPoint> forecast) {
}
```

`PvTopEntry.java`：

```java
package com.jiaxian.analytics.pv;

/** Top N 的一行。CONTENT_TYPE 维度的 id 为 null，name 为 ARTICLE / VIDEO。 */
public record PvTopEntry(Long id, String name, long pv) {
}
```

`PvDimension.java`：

```java
package com.jiaxian.analytics.pv;

public enum PvDimension {
    CHANNEL, ACCOUNT, EMPLOYEE, CONTENT_TYPE
}
```

`PvFilter.java`：

```java
package com.jiaxian.analytics.pv;

import com.jiaxian.analytics.domain.ContentType;

/** 可选过滤条件，全部为 null 表示不过滤；多个条件取交集。employeeId 指"该员工是内容的作者之一"。 */
public record PvFilter(Long channelId, Long accountId, Long employeeId, ContentType contentType) {

    public static final PvFilter NONE = new PvFilter(null, null, null, null);
}
```

`InvalidPvQueryException.java`：

```java
package com.jiaxian.analytics.pv;

/** 请求参数非法，对应 HTTP 400。 */
public class InvalidPvQueryException extends RuntimeException {

    public InvalidPvQueryException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn test -Dtest=GranularityTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`，无失败。

```bash
git add backend/src/main/java/com/jiaxian/analytics/pv backend/src/test/java/com/jiaxian/analytics/pv
git commit -m "feat: add PV granularity enum and analytics records

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 分桶、补零与周期扩展（纯逻辑）

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/pv/PvSeriesBuilder.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/pv/PvSeriesBuilderTest.java`

- [ ] **Step 1: 写失败的测试**

`PvSeriesBuilderTest.java`（今天固定为 2026-06-10，周三）：

```java
package com.jiaxian.analytics.pv;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PvSeriesBuilderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 10);

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    @Test
    void weekBucketsExpandFromToPeriodStartAndSumDays() {
        // from=06-03(周三) 向前扩展到周一 06-01，所以 06-02 的 5 也算进去
        List<DailyPv> daily = List.of(
            new DailyPv(d(2026, 6, 2), 5), new DailyPv(d(2026, 6, 3), 7), new DailyPv(d(2026, 6, 9), 4));

        List<PvBucket> buckets = PvSeriesBuilder.build(daily, Granularity.WEEK, d(2026, 6, 3), d(2026, 6, 9), TODAY);

        assertThat(buckets).containsExactly(
            new PvBucket(d(2026, 6, 1), 12, true),    // 06-01 ~ 06-07，已结束
            new PvBucket(d(2026, 6, 8), 4, false));   // 06-08 ~ 06-14，今天(06-10)还在这一周里
    }

    @Test
    void emptyPeriodsAreFilledWithZero() {
        List<DailyPv> daily = List.of(new DailyPv(d(2026, 1, 10), 3), new DailyPv(d(2026, 3, 5), 8));

        List<PvBucket> buckets = PvSeriesBuilder.build(daily, Granularity.MONTH, d(2026, 1, 15), d(2026, 3, 20), TODAY);

        assertThat(buckets).containsExactly(
            new PvBucket(d(2026, 1, 1), 3, true),
            new PvBucket(d(2026, 2, 1), 0, true),
            new PvBucket(d(2026, 3, 1), 8, true));
    }

    @Test
    void toBeyondTodayIsClampedAndTodayIsIncomplete() {
        List<DailyPv> daily = List.of(
            new DailyPv(d(2026, 6, 7), 100),   // 早于 from，必须被忽略
            new DailyPv(d(2026, 6, 9), 2),
            new DailyPv(d(2026, 6, 10), 6));

        List<PvBucket> buckets = PvSeriesBuilder.build(daily, Granularity.DAY, d(2026, 6, 8), d(2026, 6, 30), TODAY);

        assertThat(buckets).containsExactly(
            new PvBucket(d(2026, 6, 8), 0, true),
            new PvBucket(d(2026, 6, 9), 2, true),
            new PvBucket(d(2026, 6, 10), 6, false));
    }

    @Test
    void currentYearIsIncomplete() {
        List<DailyPv> daily = List.of(new DailyPv(d(2025, 3, 1), 10), new DailyPv(d(2026, 2, 1), 4));

        List<PvBucket> buckets = PvSeriesBuilder.build(daily, Granularity.YEAR, d(2025, 6, 1), d(2026, 6, 1), TODAY);

        assertThat(buckets).containsExactly(
            new PvBucket(d(2025, 1, 1), 10, true),
            new PvBucket(d(2026, 1, 1), 4, false));
    }

    @Test
    void fromAfterTodayYieldsNoBuckets() {
        List<PvBucket> buckets = PvSeriesBuilder.build(List.of(), Granularity.DAY, d(2026, 7, 1), d(2026, 7, 31), TODAY);

        assertThat(buckets).isEmpty();
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest=PvSeriesBuilderTest`
Expected: 编译失败（`PvSeriesBuilder` 不存在）。

- [ ] **Step 3: 写实现**

`PvSeriesBuilder.java`：

```java
package com.jiaxian.analytics.pv;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把"按天 PV"聚成连续的周期序列：from 向前扩展到所在周期的起点，to 向后扩展到所在周期的终点
 * （但 to 超过今天时按今天算），没有数据的周期补 0。
 */
public final class PvSeriesBuilder {

    private PvSeriesBuilder() {
    }

    public static List<PvBucket> build(List<DailyPv> daily, Granularity granularity,
                                       LocalDate from, LocalDate to, LocalDate today) {
        LocalDate effectiveTo = to.isAfter(today) ? today : to;
        if (from.isAfter(effectiveTo)) {
            return List.of();
        }

        Map<LocalDate, Long> sumByPeriod = new HashMap<>();
        for (DailyPv day : daily) {
            sumByPeriod.merge(granularity.periodStart(day.date()), day.pv(), Long::sum);
        }

        List<PvBucket> buckets = new ArrayList<>();
        LocalDate lastPeriod = granularity.periodStart(effectiveTo);
        for (LocalDate start = granularity.periodStart(from); !start.isAfter(lastPeriod);
             start = granularity.nextPeriodStart(start)) {
            boolean complete = granularity.periodEnd(start).isBefore(today);
            buckets.add(new PvBucket(start, sumByPeriod.getOrDefault(start, 0L), complete));
        }
        return buckets;
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn test -Dtest=PvSeriesBuilderTest`
Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src/main/java/com/jiaxian/analytics/pv/PvSeriesBuilder.java backend/src/test/java/com/jiaxian/analytics/pv/PvSeriesBuilderTest.java
git commit -m "feat: add PV series builder with bucketing and zero fill

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 环比、同比与预测（纯逻辑）

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/pv/PvTrendAnalyzer.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/pv/PvTrendAnalyzerTest.java`

- [ ] **Step 1: 写失败的测试**

`PvTrendAnalyzerTest.java`（所有数字都是手算的，计算过程写在注释里）：

```java
package com.jiaxian.analytics.pv;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PvTrendAnalyzerTest {

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    /** 从 first 开始按粒度生成连续周期，前 completeCount 个标为已完成。 */
    private static List<PvBucket> series(Granularity g, LocalDate first, int completeCount, long... values) {
        List<PvBucket> list = new ArrayList<>();
        LocalDate start = first;
        for (int i = 0; i < values.length; i++) {
            list.add(new PvBucket(start, values[i], i < completeCount));
            start = g.nextPeriodStart(start);
        }
        return list;
    }

    // ---- 环比 / 同比 ----

    // 2025-01..2026-02 共 14 个月：100, 0, 50, 然后 9 个 10（2025-04..12），150（2026-01），75（2026-02）
    private static final long[] MONTHLY = {100, 0, 50, 10, 10, 10, 10, 10, 10, 10, 10, 10, 150, 75};

    @Test
    void momAndYoyAreComputedAgainstPreviousPeriodAndSamePeriodLastYear() {
        List<PvBucket> buckets = series(Granularity.MONTH, d(2025, 1, 1), 14, MONTHLY);

        PvTrendAnalyzer.Analysis analysis =
            PvTrendAnalyzer.analyze(buckets, Granularity.MONTH, d(2026, 1, 1), 0);

        assertThat(analysis.points()).hasSize(2);
        PvPoint jan = analysis.points().get(0);
        assertThat(jan.periodStart()).isEqualTo(d(2026, 1, 1));
        assertThat(jan.pv()).isEqualTo(150);
        assertThat(jan.momRate()).isCloseTo(14.0, within(1e-9));   // (150-10)/10
        assertThat(jan.yoyRate()).isCloseTo(0.5, within(1e-9));    // (150-100)/100
        PvPoint feb = analysis.points().get(1);
        assertThat(feb.momRate()).isCloseTo(-0.5, within(1e-9));   // (75-150)/150
        assertThat(feb.yoyRate()).isNull();                        // 去年同期是 0
    }

    @Test
    void firstPointAndZeroBaseHaveNoMomAndMissingLastYearHasNoYoy() {
        List<PvBucket> buckets = series(Granularity.MONTH, d(2025, 1, 1), 14, MONTHLY);

        PvTrendAnalyzer.Analysis analysis =
            PvTrendAnalyzer.analyze(buckets, Granularity.MONTH, d(2025, 1, 1), 0);

        assertThat(analysis.points()).hasSize(14);
        assertThat(analysis.points().get(0).momRate()).isNull();   // 第一个点
        assertThat(analysis.points().get(0).yoyRate()).isNull();   // 没有去年的数据
        assertThat(analysis.points().get(2).momRate()).isNull();   // 上一期是 0
        assertThat(analysis.points().get(3).momRate()).isCloseTo(-0.8, within(1e-9)); // (10-50)/50
    }

    // ---- 预测 ----

    @Test
    void forecastUsesMovingAveragePlusDrift() {
        // 窗口 3：已完成 10..60，2026-07 是进行中的周期（5）不参与。
        // ma = (40+50+60)/3 = 50；前一窗口 (10+20+30)/3 = 20；slope = (50-20)/3 = 10；
        // σ = 样本标准差(40,50,60) = sqrt((100+0+100)/2) = 10
        List<PvBucket> buckets = series(Granularity.MONTH, d(2026, 1, 1), 6, 10, 20, 30, 40, 50, 60, 5);

        List<ForecastPoint> forecast =
            PvTrendAnalyzer.analyze(buckets, Granularity.MONTH, d(2026, 1, 1), 2).forecast();

        assertThat(forecast).hasSize(2);
        assertForecast(forecast.get(0), d(2026, 7, 1), 60, 50, 70);   // k=1：从进行中的 7 月开始
        assertForecast(forecast.get(1), d(2026, 8, 1), 70, 60, 80);   // k=2
    }

    @Test
    void slopeIsZeroWhenFewerThanTwoWindowsAreComplete() {
        // 只有 3 个已完成点（N=3，不足 2N）：ma = 40，slope = 0，σ = 样本标准差(30,40,50) = 10
        List<PvBucket> buckets = series(Granularity.MONTH, d(2026, 1, 1), 3, 30, 40, 50);

        List<ForecastPoint> forecast =
            PvTrendAnalyzer.analyze(buckets, Granularity.MONTH, d(2026, 1, 1), 2).forecast();

        assertThat(forecast).hasSize(2);
        assertForecast(forecast.get(0), d(2026, 4, 1), 40, 30, 50);
        assertForecast(forecast.get(1), d(2026, 5, 1), 40, 30, 50);
    }

    @Test
    void noForecastWhenFewerThanWindowPointsAreComplete() {
        List<PvBucket> buckets = series(Granularity.MONTH, d(2026, 1, 1), 2, 30, 40, 7);

        assertThat(PvTrendAnalyzer.analyze(buckets, Granularity.MONTH, d(2026, 1, 1), 3).forecast()).isEmpty();
    }

    @Test
    void zeroForecastPeriodsYieldsNoForecast() {
        List<PvBucket> buckets = series(Granularity.MONTH, d(2026, 1, 1), 6, 10, 20, 30, 40, 50, 60);

        assertThat(PvTrendAnalyzer.analyze(buckets, Granularity.MONTH, d(2026, 1, 1), 0).forecast()).isEmpty();
    }

    @Test
    void forecastAndLowerBoundAreClampedAtZero() {
        // ma = (40+20+0)/3 = 20；前一窗口 (100+80+60)/3 = 80；slope = (20-80)/3 = -20
        // k=1：20-20 = 0；k=2：-20 → 0。σ = 样本标准差(40,20,0) = 20；下限 max(0, 0-20) = 0，上限 0+20
        List<PvBucket> buckets = series(Granularity.MONTH, d(2026, 1, 1), 6, 100, 80, 60, 40, 20, 0);

        List<ForecastPoint> forecast =
            PvTrendAnalyzer.analyze(buckets, Granularity.MONTH, d(2026, 1, 1), 2).forecast();

        assertForecast(forecast.get(0), d(2026, 7, 1), 0, 0, 20);
        assertForecast(forecast.get(1), d(2026, 8, 1), 0, 0, 20);
    }

    @Test
    void dayWindowIsSeven() {
        // 14 天 1..14：最近 7 天 8..14 均值 11；前 7 天 1..7 均值 4；slope = (11-4)/7 = 1
        // σ = sqrt(Σ(x-11)² / 6) = sqrt(28/6)
        List<PvBucket> buckets = series(Granularity.DAY, d(2026, 5, 1), 14,
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);

        List<ForecastPoint> forecast =
            PvTrendAnalyzer.analyze(buckets, Granularity.DAY, d(2026, 5, 1), 1).forecast();

        double sigma = Math.sqrt(28.0 / 6);
        assertForecast(forecast.get(0), d(2026, 5, 15), 12, 12 - sigma, 12 + sigma);
    }

    @Test
    void weekWindowIsFourAndYearWindowIsTwo() {
        // 周：窗口 4，已完成 4 个点全是 10 → ma=10, slope=0, σ=0
        List<ForecastPoint> weekly = PvTrendAnalyzer.analyze(
            series(Granularity.WEEK, d(2026, 3, 2), 4, 10, 10, 10, 10), Granularity.WEEK, d(2026, 3, 2), 1).forecast();
        assertForecast(weekly.get(0), d(2026, 3, 30), 10, 10, 10);

        // 年：窗口 2，ma = 150，slope = 0（不足 4 个点），σ = sqrt(((50)²+(50)²)/1) = sqrt(5000)
        List<ForecastPoint> yearly = PvTrendAnalyzer.analyze(
            series(Granularity.YEAR, d(2024, 1, 1), 2, 100, 200), Granularity.YEAR, d(2024, 1, 1), 1).forecast();
        double sigma = Math.sqrt(5000);
        assertForecast(yearly.get(0), d(2026, 1, 1), 150, 150 - sigma, 150 + sigma);

        // 年：只有 1 个已完成点，不够窗口 → 不预测
        assertThat(PvTrendAnalyzer.analyze(
            series(Granularity.YEAR, d(2025, 1, 1), 1, 100), Granularity.YEAR, d(2025, 1, 1), 1).forecast()).isEmpty();
    }

    private static void assertForecast(ForecastPoint actual, LocalDate periodStart,
                                       double value, double lower, double upper) {
        assertThat(actual.periodStart()).isEqualTo(periodStart);
        assertThat(actual.value()).isCloseTo(value, within(1e-9));
        assertThat(actual.lower()).isCloseTo(lower, within(1e-9));
        assertThat(actual.upper()).isCloseTo(upper, within(1e-9));
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest=PvTrendAnalyzerTest`
Expected: 编译失败（`PvTrendAnalyzer` 不存在）。

- [ ] **Step 3: 写实现**

`PvTrendAnalyzer.java`：

```java
package com.jiaxian.analytics.pv;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯逻辑：在连续的周期序列上计算环比、同比和预测。
 *
 * <p>buckets 应当包含请求范围之前多取的一年，这样范围内最早的点也能算出同比；
 * 只有 periodStart 不早于 visibleFrom 的点会放进结果，预测则基于整个序列里已完成的周期。
 */
public final class PvTrendAnalyzer {

    public record Analysis(List<PvPoint> points, List<ForecastPoint> forecast) {
    }

    private PvTrendAnalyzer() {
    }

    public static Analysis analyze(List<PvBucket> buckets, Granularity granularity,
                                   LocalDate visibleFrom, int forecastPeriods) {
        Map<LocalDate, Long> pvByStart = new HashMap<>();
        for (PvBucket bucket : buckets) {
            pvByStart.put(bucket.periodStart(), bucket.pv());
        }

        List<PvPoint> points = new ArrayList<>();
        for (int i = 0; i < buckets.size(); i++) {
            PvBucket bucket = buckets.get(i);
            if (bucket.periodStart().isBefore(visibleFrom)) {
                continue;
            }
            Double mom = i == 0 ? null : rate(bucket.pv(), buckets.get(i - 1).pv());
            Long lastYear = pvByStart.get(granularity.sameLastYear(bucket.periodStart()));
            Double yoy = lastYear == null ? null : rate(bucket.pv(), lastYear);
            points.add(new PvPoint(bucket.periodStart(), bucket.pv(), mom, yoy, bucket.complete()));
        }
        return new Analysis(points, forecast(buckets, granularity, forecastPeriods));
    }

    private static Double rate(long current, long base) {
        return base == 0 ? null : (current - base) / (double) base;
    }

    private static List<ForecastPoint> forecast(List<PvBucket> buckets, Granularity granularity, int periods) {
        List<PvBucket> complete = buckets.stream().filter(PvBucket::complete).toList();
        int window = granularity.forecastWindow();
        int size = complete.size();
        if (periods <= 0 || size < window) {
            return List.of();
        }

        double ma = mean(complete, size - window, size);
        double slope = size >= 2 * window ? (ma - mean(complete, size - 2 * window, size - window)) / window : 0;
        double sigma = sampleStdDev(complete, size - window, size, ma);

        List<ForecastPoint> forecast = new ArrayList<>();
        LocalDate periodStart = granularity.nextPeriodStart(complete.get(size - 1).periodStart());
        for (int k = 1; k <= periods; k++) {
            double value = Math.max(0, ma + slope * k);
            forecast.add(new ForecastPoint(periodStart, value, Math.max(0, value - sigma), value + sigma));
            periodStart = granularity.nextPeriodStart(periodStart);
        }
        return forecast;
    }

    private static double mean(List<PvBucket> buckets, int fromInclusive, int toExclusive) {
        double sum = 0;
        for (int i = fromInclusive; i < toExclusive; i++) {
            sum += buckets.get(i).pv();
        }
        return sum / (toExclusive - fromInclusive);
    }

    private static double sampleStdDev(List<PvBucket> buckets, int fromInclusive, int toExclusive, double mean) {
        int n = toExclusive - fromInclusive;
        if (n < 2) {
            return 0;
        }
        double squares = 0;
        for (int i = fromInclusive; i < toExclusive; i++) {
            double diff = buckets.get(i).pv() - mean;
            squares += diff * diff;
        }
        return Math.sqrt(squares / (n - 1));
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn test -Dtest=PvTrendAnalyzerTest`
Expected: `Tests run: 9, Failures: 0, Errors: 0`

- [ ] **Step 5: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src/main/java/com/jiaxian/analytics/pv/PvTrendAnalyzer.java backend/src/test/java/com/jiaxian/analytics/pv/PvTrendAnalyzerTest.java
git commit -m "feat: add PV trend analyzer with MoM, YoY and forecast

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 汇总表与重算服务

**Files:**
- Modify: `backend/src/main/resources/schema.sql`（文件末尾追加）
- Create: `backend/src/main/java/com/jiaxian/analytics/pv/PvSummaryService.java`
- Create: `backend/src/test/java/com/jiaxian/analytics/pv/PvTestFixtures.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/pv/PvSummaryServiceIT.java`

- [ ] **Step 1: 写测试夹具（后面几个 IT 共用）**

`PvTestFixtures.java`（普通类，不是 Spring Bean；每个测试用自己注入的 Repository 构造它）：

```java
package com.jiaxian.analytics.pv;

import com.jiaxian.analytics.domain.Account;
import com.jiaxian.analytics.domain.AccountType;
import com.jiaxian.analytics.domain.Channel;
import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.ContentAuthor;
import com.jiaxian.analytics.domain.ContentType;
import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.domain.PvLog;
import com.jiaxian.analytics.repository.AccountRepository;
import com.jiaxian.analytics.repository.ChannelRepository;
import com.jiaxian.analytics.repository.ContentAuthorRepository;
import com.jiaxian.analytics.repository.ContentRepository;
import com.jiaxian.analytics.repository.EmployeeRepository;
import com.jiaxian.analytics.repository.PvLogRepository;

import java.time.LocalDateTime;

/** 集成测试用的夹具构造器。名字统一带"PV测试"前缀，避免和种子数据（频道、权重）重名。 */
class PvTestFixtures {

    private static final String BATCH = "pv-test";

    private final ChannelRepository channelRepository;
    private final AccountRepository accountRepository;
    private final EmployeeRepository employeeRepository;
    private final ContentRepository contentRepository;
    private final ContentAuthorRepository contentAuthorRepository;
    private final PvLogRepository pvLogRepository;

    PvTestFixtures(ChannelRepository channelRepository, AccountRepository accountRepository,
                   EmployeeRepository employeeRepository, ContentRepository contentRepository,
                   ContentAuthorRepository contentAuthorRepository, PvLogRepository pvLogRepository) {
        this.channelRepository = channelRepository;
        this.accountRepository = accountRepository;
        this.employeeRepository = employeeRepository;
        this.contentRepository = contentRepository;
        this.contentAuthorRepository = contentAuthorRepository;
        this.pvLogRepository = pvLogRepository;
    }

    Channel channel(String name) {
        return channelRepository.save(Channel.builder().name(name).category("专业领域").build());
    }

    Account account(String name) {
        return accountRepository.save(
            Account.builder().name(name).type(AccountType.PERSONAL).simulationBatchId(BATCH).build());
    }

    Employee employee(String name) {
        return employeeRepository.save(Employee.builder().name(name).simulationBatchId(BATCH).build());
    }

    /** 创建内容并登记作者（authors 里同一个人出现两次就会产生重复的 content_author 行）。 */
    Content content(ContentType type, Channel channel, Account account, Employee... authors) {
        Content content = contentRepository.save(Content.builder()
            .type(type).channelId(channel.getId()).accountId(account.getId())
            .publishTime(LocalDateTime.of(2019, 1, 1, 0, 0))
            .popularityWeight(0.5).simulationBatchId(BATCH).build());
        for (Employee author : authors) {
            contentAuthorRepository.save(ContentAuthor.builder()
                .contentId(content.getId()).employeeId(author.getId()).simulationBatchId(BATCH).build());
        }
        return content;
    }

    void views(Content content, Employee viewer, LocalDateTime... viewTimes) {
        for (LocalDateTime viewTime : viewTimes) {
            pvLogRepository.save(PvLog.builder()
                .contentId(content.getId()).viewerEmployeeId(viewer.getId())
                .viewTime(viewTime).simulationBatchId(BATCH).build());
        }
    }

    /** 同一天内重复 count 次浏览。 */
    void viewsOnDay(Content content, Employee viewer, int year, int month, int day, int count) {
        LocalDateTime[] times = new LocalDateTime[count];
        for (int i = 0; i < count; i++) {
            times[i] = LocalDateTime.of(year, month, day, 9, i % 60);
        }
        views(content, viewer, times);
    }
}
```

> 说明：`Channel`/`Account`/`Employee` 的 `@Builder` 字段按 Plan 1 现有测试 `FactAndPointsRepositoryIT` 的写法（`Channel.builder().name().category()`、`Account.builder().name().type().simulationBatchId()`、`Employee.builder().name().simulationBatchId()`）。

- [ ] **Step 2: 写失败的测试**

`PvSummaryServiceIT.java`：

```java
package com.jiaxian.analytics.pv;

import com.jiaxian.analytics.domain.Account;
import com.jiaxian.analytics.domain.Channel;
import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.ContentType;
import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.repository.AccountRepository;
import com.jiaxian.analytics.repository.ChannelRepository;
import com.jiaxian.analytics.repository.ContentAuthorRepository;
import com.jiaxian.analytics.repository.ContentRepository;
import com.jiaxian.analytics.repository.EmployeeRepository;
import com.jiaxian.analytics.repository.PvLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PvSummaryServiceIT {

    @Autowired private PvSummaryService summaryService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private ContentAuthorRepository contentAuthorRepository;
    @Autowired private PvLogRepository pvLogRepository;

    private PvTestFixtures fixtures;
    private Employee viewer;
    private Content content;

    @BeforeEach
    void setUp() {
        fixtures = new PvTestFixtures(channelRepository, accountRepository, employeeRepository,
            contentRepository, contentAuthorRepository, pvLogRepository);
        Channel channel = fixtures.channel("PV测试频道-汇总");
        Account account = fixtures.account("PV测试账号-汇总");
        Employee author = fixtures.employee("PV测试作者-汇总");
        viewer = fixtures.employee("PV测试读者-汇总");
        content = fixtures.content(ContentType.ARTICLE, channel, account, author);
        // 2019-05：01 日 2 次（含 00:00:00 和 23:59:59 两个边界），02 日 1 次，04 日 1 次
        fixtures.views(content, viewer,
            LocalDateTime.of(2019, 5, 1, 0, 0, 0), LocalDateTime.of(2019, 5, 1, 23, 59, 59),
            LocalDateTime.of(2019, 5, 2, 10, 0), LocalDateTime.of(2019, 5, 4, 8, 30));
    }

    private Map<LocalDate, Long> summary() {
        Map<LocalDate, Long> rows = new TreeMap<>();
        jdbc.query("SELECT summary_date, pv_count FROM pv_summary_daily WHERE content_id = ?",
            rs -> {
                rows.put(rs.getTimestamp(1).toLocalDateTime().toLocalDate(), rs.getLong(2));
            }, content.getId());
        return rows;
    }

    @Test
    void rebuildWritesOneRowPerContentAndDay() {
        int written = summaryService.rebuild(LocalDate.of(2019, 5, 1), LocalDate.of(2019, 5, 31));

        assertThat(written).isEqualTo(3);
        assertThat(summary()).containsExactly(
            Map.entry(LocalDate.of(2019, 5, 1), 2L),
            Map.entry(LocalDate.of(2019, 5, 2), 1L),
            Map.entry(LocalDate.of(2019, 5, 4), 1L));
    }

    @Test
    void rebuildIsIdempotent() {
        summaryService.rebuild(LocalDate.of(2019, 5, 1), LocalDate.of(2019, 5, 31));
        Map<LocalDate, Long> first = summary();

        int writtenAgain = summaryService.rebuild(LocalDate.of(2019, 5, 1), LocalDate.of(2019, 5, 31));

        assertThat(writtenAgain).isEqualTo(3);
        assertThat(summary()).isEqualTo(first);
    }

    @Test
    void rebuildOnlyTouchesTheRequestedRangeAndBothEndDaysAreIncluded() {
        summaryService.rebuild(LocalDate.of(2019, 5, 1), LocalDate.of(2019, 5, 31));
        // 5 月 2 日又多了一次浏览；5 月 4 日的浏览被删掉
        fixtures.views(content, viewer, LocalDateTime.of(2019, 5, 2, 18, 0));
        jdbc.update("DELETE FROM pv_log WHERE content_id = ? AND view_time >= ? AND view_time < ?",
            content.getId(), java.sql.Timestamp.valueOf("2019-05-04 00:00:00"), java.sql.Timestamp.valueOf("2019-05-05 00:00:00"));

        int written = summaryService.rebuild(LocalDate.of(2019, 5, 2), LocalDate.of(2019, 5, 2));

        assertThat(written).isEqualTo(1);
        assertThat(summary()).containsExactly(
            Map.entry(LocalDate.of(2019, 5, 1), 2L),   // 范围外，保持不变
            Map.entry(LocalDate.of(2019, 5, 2), 2L),   // 重算了
            Map.entry(LocalDate.of(2019, 5, 4), 1L));  // 范围外，旧数据保持不变
    }

    @Test
    void rebuildRemovesStaleRowsInTheRange() {
        summaryService.rebuild(LocalDate.of(2019, 5, 1), LocalDate.of(2019, 5, 31));
        jdbc.update("DELETE FROM pv_log WHERE content_id = ? AND view_time >= ? AND view_time < ?",
            content.getId(), java.sql.Timestamp.valueOf("2019-05-04 00:00:00"), java.sql.Timestamp.valueOf("2019-05-05 00:00:00"));

        summaryService.rebuild(LocalDate.of(2019, 5, 1), LocalDate.of(2019, 5, 31));

        assertThat(summary()).containsOnlyKeys(LocalDate.of(2019, 5, 1), LocalDate.of(2019, 5, 2));
    }

    @Test
    void nullBoundsRebuildEverythingAndTotalsMatchPvLog() {
        summaryService.rebuild(null, null);

        Long summaryTotal = jdbc.queryForObject("SELECT COALESCE(SUM(pv_count), 0) FROM pv_summary_daily", Long.class);
        Long pvLogTotal = jdbc.queryForObject("SELECT COUNT(*) FROM pv_log", Long.class);
        assertThat(summaryTotal).isEqualTo(pvLogTotal);
        assertThat(summary()).hasSize(3);
    }

    @Test
    void fromAfterToIsRejected() {
        assertThatThrownBy(() -> summaryService.rebuild(LocalDate.of(2019, 5, 31), LocalDate.of(2019, 5, 1)))
            .isInstanceOf(InvalidPvQueryException.class);
    }

    @Test
    void deletingContentCascadesToSummaryRows() {
        summaryService.rebuild(LocalDate.of(2019, 5, 1), LocalDate.of(2019, 5, 31));
        assertThat(summary()).isNotEmpty();

        jdbc.update("DELETE FROM pv_log WHERE content_id = ?", content.getId());
        jdbc.update("DELETE FROM content_author WHERE content_id = ?", content.getId());
        jdbc.update("DELETE FROM content WHERE id = ?", content.getId());

        assertThat(summary()).isEmpty();
    }
}
```

- [ ] **Step 3: 运行，确认失败**

Run: `cd backend && mvn test -Dtest=PvSummaryServiceIT`
Expected: 编译失败（`PvSummaryService` 不存在）。

- [ ] **Step 4: 追加表结构**

在 `backend/src/main/resources/schema.sql` 文件**末尾**追加（此时 `content` 已经建好）：

```sql

CREATE TABLE IF NOT EXISTS pv_summary_daily (
    content_id   BIGINT NOT NULL REFERENCES content(id) ON DELETE CASCADE,
    summary_date DATE NOT NULL,
    pv_count     BIGINT NOT NULL,
    PRIMARY KEY (content_id, summary_date)
);
```

- [ ] **Step 5: 写实现**

`PvSummaryService.java`：

```java
package com.jiaxian.analytics.pv;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 pv_log 按"内容 × 天"重算进 pv_summary_daily。先删后插，同一范围重复重算结果完全一样。
 *
 * <p>OpenGauss 在 A 兼容模式下 DATE 实际是 timestamp，且 CAST(ts AS DATE) 会保留时间部分，
 * 所以这里用 date_trunc('day', ...) 截断；范围用 [from 当天零点, to 次日零点) 表达，不对列做函数运算。
 */
@Service
public class PvSummaryService {

    private final JdbcTemplate jdbc;

    public PvSummaryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** from/to 任一为 null 表示该侧不设界。返回写入的行数。 */
    @Transactional
    public int rebuild(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidPvQueryException("from must not be after to");
        }

        StringBuilder delete = new StringBuilder("DELETE FROM pv_summary_daily WHERE 1 = 1");
        StringBuilder insert = new StringBuilder(
            "INSERT INTO pv_summary_daily (content_id, summary_date, pv_count) "
                + "SELECT content_id, date_trunc('day', view_time), count(*) FROM pv_log WHERE 1 = 1");
        List<Object> deleteArgs = new ArrayList<>();
        List<Object> insertArgs = new ArrayList<>();

        if (from != null) {
            Timestamp lower = Timestamp.valueOf(from.atStartOfDay());
            delete.append(" AND summary_date >= ?");
            deleteArgs.add(lower);
            insert.append(" AND view_time >= ?");
            insertArgs.add(lower);
        }
        if (to != null) {
            Timestamp upperExclusive = Timestamp.valueOf(to.plusDays(1).atStartOfDay());
            delete.append(" AND summary_date < ?");
            deleteArgs.add(upperExclusive);
            insert.append(" AND view_time < ?");
            insertArgs.add(upperExclusive);
        }
        insert.append(" GROUP BY content_id, date_trunc('day', view_time)");

        jdbc.update(delete.toString(), deleteArgs.toArray());
        return jdbc.update(insert.toString(), insertArgs.toArray());
    }
}
```

- [ ] **Step 6: 运行，确认通过**

Run: `cd backend && mvn test -Dtest=PvSummaryServiceIT`
Expected: `Tests run: 7, Failures: 0, Errors: 0`

如果 `rebuildWritesOneRowPerContentAndDay` 里 `written` 不是 3，先用 gsql 查 2019-05 是否有别的数据；如果 `summary()` 的日期带了时间或被分成多组，说明 `date_trunc` 没生效，回头检查 SQL。

- [ ] **Step 7: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`（包含启动时 `schema.sql` 重复执行的幂等性：`CREATE TABLE IF NOT EXISTS`）。

```bash
git add backend/src/main/resources/schema.sql backend/src/main/java/com/jiaxian/analytics/pv/PvSummaryService.java backend/src/test/java/com/jiaxian/analytics/pv/PvTestFixtures.java backend/src/test/java/com/jiaxian/analytics/pv/PvSummaryServiceIT.java
git commit -m "feat: add pv_summary_daily table and rebuild service

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 定时重算

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/pv/PvConfig.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/pv/PvSummaryScheduler.java`
- Modify: `backend/src/test/resources/application.properties`
- Test: `backend/src/test/java/com/jiaxian/analytics/pv/PvSummarySchedulerTest.java`

- [ ] **Step 1: 写失败的测试**

`PvSummarySchedulerTest.java`：

```java
package com.jiaxian.analytics.pv;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PvSummarySchedulerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-04T00:10:00Z"), ZoneOffset.UTC);
    private final PvSummaryService summaryService = mock(PvSummaryService.class);

    @Test
    void rebuildsTheLastNDaysIncludingToday() {
        new PvSummaryScheduler(summaryService, clock, 3).rebuildRecentDays();

        verify(summaryService).rebuild(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 4));
    }

    @Test
    void atLeastTodayIsRebuiltEvenIfDaysIsMisconfigured() {
        new PvSummaryScheduler(summaryService, clock, 0).rebuildRecentDays();

        verify(summaryService).rebuild(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 4));
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest=PvSummarySchedulerTest`
Expected: 编译失败（`PvSummaryScheduler` 不存在）。

- [ ] **Step 3: 写实现**

`PvConfig.java`：

```java
package com.jiaxian.analytics.pv;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class PvConfig {

    /** 统一的时钟 Bean，业务代码取"今天"都走它，测试里可以替换成固定时钟。 */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
```

`PvSummaryScheduler.java`：

```java
package com.jiaxian.analytics.pv;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/** 每天凌晨把最近 N 天（含今天）重算一遍，把实时事件写入的 pv_log 补进汇总表。 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "analytics.pv.schedule.enabled", havingValue = "true", matchIfMissing = true)
public class PvSummaryScheduler {

    private final PvSummaryService summaryService;
    private final Clock clock;
    private final int rebuildDays;

    public PvSummaryScheduler(PvSummaryService summaryService, Clock clock,
                              @Value("${analytics.pv.rebuild-days:3}") int rebuildDays) {
        this.summaryService = summaryService;
        this.clock = clock;
        this.rebuildDays = rebuildDays;
    }

    @Scheduled(cron = "${analytics.pv.rebuild-cron:0 10 0 * * *}")
    public void rebuildRecentDays() {
        LocalDate today = LocalDate.now(clock);
        summaryService.rebuild(today.minusDays(Math.max(rebuildDays, 1) - 1L), today);
    }
}
```

`backend/src/test/resources/application.properties` 追加一行（文件里已有 `realtime.consumer.auto-startup=false`）：

```properties
analytics.pv.schedule.enabled=false
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn test -Dtest=PvSummarySchedulerTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`（测试环境里定时任务被关闭，不会干扰其他测试；Spring 上下文能正常启动，说明 `Clock` Bean 没有和别的 Bean 冲突）。

```bash
git add backend/src/main/java/com/jiaxian/analytics/pv/PvConfig.java backend/src/main/java/com/jiaxian/analytics/pv/PvSummaryScheduler.java backend/src/test/java/com/jiaxian/analytics/pv/PvSummarySchedulerTest.java backend/src/test/resources/application.properties
git commit -m "feat: add scheduled PV summary rebuild

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 按维度查询仓库（按天 PV、Top N）

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/pv/PvDailyQueryRepository.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/pv/PvDailyQueryRepositoryIT.java`

- [ ] **Step 1: 写失败的测试**

夹具数据（2019-06，库里没有这个月的真实数据，所以断言可以精确）：

| 内容 | 类型 | 频道 | 账号 | 作者 | 浏览 |
|---|---|---|---|---|---|
| c1 | ARTICLE | 频道A | 账号1 | 员工1 | 06-01 ×2，06-02 ×1 |
| c2 | VIDEO | 频道A | 账号2 | 员工1 + 员工2（合著） | 06-02 ×4 |
| c3 | ARTICLE | 频道B | 账号1 | 员工3 + 员工3（重复作者行） | 06-01 ×4 |

手算结果：全站按天 06-01 → 2+4=6，06-02 → 1+4=5；Top：频道A 7 / 频道B 4；账号1 7 / 账号2 4；员工1 7（c1 的 3 + c2 的 4）、员工2 4、员工3 4（重复作者行不能算成 8）；类型 ARTICLE 7 / VIDEO 4。

`PvDailyQueryRepositoryIT.java`：

```java
package com.jiaxian.analytics.pv;

import com.jiaxian.analytics.domain.Account;
import com.jiaxian.analytics.domain.Channel;
import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.ContentType;
import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.repository.AccountRepository;
import com.jiaxian.analytics.repository.ChannelRepository;
import com.jiaxian.analytics.repository.ContentAuthorRepository;
import com.jiaxian.analytics.repository.ContentRepository;
import com.jiaxian.analytics.repository.EmployeeRepository;
import com.jiaxian.analytics.repository.PvLogRepository;
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
class PvDailyQueryRepositoryIT {

    private static final LocalDate FROM = LocalDate.of(2019, 6, 1);
    private static final LocalDate TO = LocalDate.of(2019, 6, 30);
    private static final LocalDate JUN_1 = LocalDate.of(2019, 6, 1);
    private static final LocalDate JUN_2 = LocalDate.of(2019, 6, 2);

    @Autowired private PvDailyQueryRepository repository;
    @Autowired private PvSummaryService summaryService;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private ContentAuthorRepository contentAuthorRepository;
    @Autowired private PvLogRepository pvLogRepository;

    private Channel channelA;
    private Channel channelB;
    private Account account1;
    private Account account2;
    private Employee employee1;
    private Employee employee2;
    private Employee employee3;

    @BeforeEach
    void setUp() {
        PvTestFixtures fixtures = new PvTestFixtures(channelRepository, accountRepository, employeeRepository,
            contentRepository, contentAuthorRepository, pvLogRepository);
        channelA = fixtures.channel("PV测试频道A");
        channelB = fixtures.channel("PV测试频道B");
        account1 = fixtures.account("PV测试账号1");
        account2 = fixtures.account("PV测试账号2");
        employee1 = fixtures.employee("PV测试员工1");
        employee2 = fixtures.employee("PV测试员工2");
        employee3 = fixtures.employee("PV测试员工3");
        Employee viewer = fixtures.employee("PV测试读者");

        Content c1 = fixtures.content(ContentType.ARTICLE, channelA, account1, employee1);
        Content c2 = fixtures.content(ContentType.VIDEO, channelA, account2, employee1, employee2);
        Content c3 = fixtures.content(ContentType.ARTICLE, channelB, account1, employee3, employee3);

        fixtures.viewsOnDay(c1, viewer, 2019, 6, 1, 2);
        fixtures.viewsOnDay(c1, viewer, 2019, 6, 2, 1);
        fixtures.viewsOnDay(c2, viewer, 2019, 6, 2, 4);
        fixtures.viewsOnDay(c3, viewer, 2019, 6, 1, 4);

        summaryService.rebuild(FROM, TO);
    }

    private List<DailyPv> daily(PvFilter filter) {
        return repository.dailyPv(FROM, TO, filter);
    }

    // ---- 按天 PV ----

    @Test
    void dailyPvWithoutFilterSumsAllContent() {
        assertThat(daily(PvFilter.NONE)).containsExactly(new DailyPv(JUN_1, 6), new DailyPv(JUN_2, 5));
    }

    @Test
    void dailyPvRespectsDateRangeInclusively() {
        assertThat(repository.dailyPv(JUN_2, JUN_2, PvFilter.NONE)).containsExactly(new DailyPv(JUN_2, 5));
    }

    @Test
    void dailyPvFiltersByChannel() {
        assertThat(daily(new PvFilter(channelA.getId(), null, null, null)))
            .containsExactly(new DailyPv(JUN_1, 2), new DailyPv(JUN_2, 5));
    }

    @Test
    void dailyPvFiltersByAccount() {
        assertThat(daily(new PvFilter(null, account1.getId(), null, null)))
            .containsExactly(new DailyPv(JUN_1, 6), new DailyPv(JUN_2, 1));
    }

    @Test
    void dailyPvFiltersByContentType() {
        assertThat(daily(new PvFilter(null, null, null, ContentType.VIDEO)))
            .containsExactly(new DailyPv(JUN_2, 4));
    }

    @Test
    void dailyPvFiltersByCoAuthoringEmployee() {
        assertThat(daily(new PvFilter(null, null, employee1.getId(), null)))
            .containsExactly(new DailyPv(JUN_1, 2), new DailyPv(JUN_2, 5));
        // 员工2只是 c2 的共同作者，同样拿到 c2 的完整 PV
        assertThat(daily(new PvFilter(null, null, employee2.getId(), null)))
            .containsExactly(new DailyPv(JUN_2, 4));
    }

    @Test
    void duplicateContentAuthorRowsDoNotDoubleCount() {
        assertThat(daily(new PvFilter(null, null, employee3.getId(), null)))
            .containsExactly(new DailyPv(JUN_1, 4));
    }

    @Test
    void filtersAreCombinedWithAnd() {
        assertThat(daily(new PvFilter(channelA.getId(), null, null, ContentType.VIDEO)))
            .containsExactly(new DailyPv(JUN_2, 4));
        assertThat(daily(new PvFilter(channelA.getId(), null, employee2.getId(), null)))
            .containsExactly(new DailyPv(JUN_2, 4));
        assertThat(daily(new PvFilter(channelB.getId(), null, null, ContentType.VIDEO))).isEmpty();
    }

    @Test
    void unknownFilterIdYieldsNoRows() {
        assertThat(daily(new PvFilter(-1L, null, null, null))).isEmpty();
    }

    // ---- Top N ----

    @Test
    void topChannels() {
        assertThat(repository.top(PvDimension.CHANNEL, FROM, TO, PvFilter.NONE, 10)).containsExactly(
            new PvTopEntry(channelA.getId(), "PV测试频道A", 7),
            new PvTopEntry(channelB.getId(), "PV测试频道B", 4));
    }

    @Test
    void topAccounts() {
        assertThat(repository.top(PvDimension.ACCOUNT, FROM, TO, PvFilter.NONE, 10)).containsExactly(
            new PvTopEntry(account1.getId(), "PV测试账号1", 7),
            new PvTopEntry(account2.getId(), "PV测试账号2", 4));
    }

    @Test
    void topEmployeesGiveFullCreditToCoAuthorsAndBreakTiesById() {
        assertThat(repository.top(PvDimension.EMPLOYEE, FROM, TO, PvFilter.NONE, 10)).containsExactly(
            new PvTopEntry(employee1.getId(), "PV测试员工1", 7),
            new PvTopEntry(employee2.getId(), "PV测试员工2", 4),   // 与员工3并列 4，id 小的在前
            new PvTopEntry(employee3.getId(), "PV测试员工3", 4));  // 重复作者行没有让它变成 8
    }

    @Test
    void topContentTypesHaveNullId() {
        assertThat(repository.top(PvDimension.CONTENT_TYPE, FROM, TO, PvFilter.NONE, 10)).containsExactly(
            new PvTopEntry(null, "ARTICLE", 7),
            new PvTopEntry(null, "VIDEO", 4));
    }

    @Test
    void topRespectsLimitFilterAndDateRange() {
        assertThat(repository.top(PvDimension.CHANNEL, FROM, TO, PvFilter.NONE, 1))
            .containsExactly(new PvTopEntry(channelA.getId(), "PV测试频道A", 7));

        assertThat(repository.top(PvDimension.ACCOUNT, FROM, TO, new PvFilter(channelA.getId(), null, null, null), 10))
            .containsExactly(
                new PvTopEntry(account2.getId(), "PV测试账号2", 4),
                new PvTopEntry(account1.getId(), "PV测试账号1", 3));

        // 只看 06-02：频道A = c1(1) + c2(4) = 5，频道B 没有数据
        assertThat(repository.top(PvDimension.CHANNEL, JUN_2, JUN_2, PvFilter.NONE, 10))
            .containsExactly(new PvTopEntry(channelA.getId(), "PV测试频道A", 5));
    }

    @Test
    void topRejectsLimitOutsideSafeRange() {
        // @Repository 的异常转换会把 IllegalArgumentException 包成 InvalidDataAccessApiUsageException
        assertThatThrownBy(() -> repository.top(PvDimension.CHANNEL, FROM, TO, PvFilter.NONE, 0))
            .isInstanceOf(InvalidDataAccessApiUsageException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> repository.top(PvDimension.CHANNEL, FROM, TO, PvFilter.NONE, 101))
            .isInstanceOf(InvalidDataAccessApiUsageException.class).hasMessageContaining("limit");
    }
}
```

> 说明 `topRespectsLimitFilterAndDateRange` 里的第二个断言：过滤到频道A 后，账号1 只剩 c1 的 3 次（06-01 ×2 + 06-02 ×1），账号2 是 c2 的 4 次，所以账号2（4）排在账号1（3）前面。

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest=PvDailyQueryRepositoryIT`
Expected: 编译失败（`PvDailyQueryRepository` 不存在）。

- [ ] **Step 3: 写实现**

`PvDailyQueryRepository.java`：

```java
package com.jiaxian.analytics.pv;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 pv_summary_daily 取数。频道/账号/博主/内容类型都在查询时通过 JOIN content（和 content_author）得到。
 *
 * <p>日期列在 A 兼容模式下是 timestamp：参数一律绑定 Timestamp，读取一律走 getTimestamp。
 * 一篇内容有多个共同作者时，每位作者都记完整 PV；content_author 可能有重复行，所以
 * 过滤用 EXISTS、Top N 用 SELECT DISTINCT 子查询，避免重复计数。
 */
@Repository
public class PvDailyQueryRepository {

    private static final int MAX_LIMIT = 100;

    private static final String FROM_CONTENT =
        " FROM pv_summary_daily s JOIN content c ON c.id = s.content_id";

    private final JdbcTemplate jdbc;

    public PvDailyQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 范围内每一天的 PV（只返回有数据的日子，补零由 PvSeriesBuilder 负责）。from、to 都包含。 */
    public List<DailyPv> dailyPv(LocalDate from, LocalDate to, PvFilter filter) {
        Where where = where(from, to, filter);
        String sql = "SELECT s.summary_date, SUM(s.pv_count)" + FROM_CONTENT + where.sql()
            + " GROUP BY s.summary_date ORDER BY s.summary_date";
        return jdbc.query(sql,
            (rs, rowNum) -> new DailyPv(rs.getTimestamp(1).toLocalDateTime().toLocalDate(), rs.getLong(2)),
            where.args().toArray());
    }

    /** 按维度汇总 PV 的前 limit 名：PV 降序，PV 相同按 id（内容类型按名字）升序。 */
    public List<PvTopEntry> top(PvDimension dimension, LocalDate from, LocalDate to, PvFilter filter, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            // LIMIT 以字面量写进 SQL（OpenGauss 不接受绑定参数形式的分页），所以必须先确认是安全的整数范围。
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        Where where = where(from, to, filter);
        String sql = switch (dimension) {
            case CHANNEL -> topSql("ch.id", "ch.name",
                " JOIN channel ch ON ch.id = c.channel_id", "ch.id, ch.name", "ch.id", where, limit);
            case ACCOUNT -> topSql("a.id", "a.name",
                " JOIN account a ON a.id = c.account_id", "a.id, a.name", "a.id", where, limit);
            case EMPLOYEE -> topSql("e.id", "e.name",
                " JOIN (SELECT DISTINCT content_id, employee_id FROM content_author) ca ON ca.content_id = c.id"
                    + " JOIN employee e ON e.id = ca.employee_id", "e.id, e.name", "e.id", where, limit);
            case CONTENT_TYPE -> topSql("CAST(NULL AS BIGINT)", "c.type",
                "", "c.type", "c.type", where, limit);
        };
        return jdbc.query(sql, (rs, rowNum) -> {
            long id = rs.getLong(1);
            Long nullableId = rs.wasNull() ? null : id;
            return new PvTopEntry(nullableId, rs.getString(2), rs.getLong(3));
        }, where.args().toArray());
    }

    private static String topSql(String idExpr, String nameExpr, String extraJoins,
                                 String groupBy, String tieBreaker, Where where, int limit) {
        return "SELECT " + idExpr + ", " + nameExpr + ", SUM(s.pv_count)" + FROM_CONTENT + extraJoins
            + where.sql() + " GROUP BY " + groupBy
            + " ORDER BY SUM(s.pv_count) DESC, " + tieBreaker + " ASC LIMIT " + limit;
    }

    private static Where where(LocalDate from, LocalDate to, PvFilter filter) {
        Where where = new Where();
        where.and("s.summary_date >= ?", Timestamp.valueOf(from.atStartOfDay()));
        where.and("s.summary_date < ?", Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        if (filter.channelId() != null) {
            where.and("c.channel_id = ?", filter.channelId());
        }
        if (filter.accountId() != null) {
            where.and("c.account_id = ?", filter.accountId());
        }
        if (filter.contentType() != null) {
            where.and("c.type = ?", filter.contentType().name());
        }
        if (filter.employeeId() != null) {
            where.and("EXISTS (SELECT 1 FROM content_author fa"
                + " WHERE fa.content_id = c.id AND fa.employee_id = ?)", filter.employeeId());
        }
        return where;
    }

    private static final class Where {
        private final List<String> clauses = new ArrayList<>();
        private final List<Object> args = new ArrayList<>();

        void and(String clause, Object arg) {
            clauses.add(clause);
            args.add(arg);
        }

        String sql() {
            return " WHERE " + String.join(" AND ", clauses);
        }

        List<Object> args() {
            return args;
        }
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn test -Dtest=PvDailyQueryRepositoryIT`
Expected: `Tests run: 15, Failures: 0, Errors: 0`

如果 `CAST(NULL AS BIGINT)` 或 `ORDER BY SUM(...)` 在 OpenGauss 报语法/类型错误，用 gsql 直接试那条 SQL，改成等价写法（例如内容类型维度的 id 改成 `0` 再在 Java 里映射成 null），并在提交说明里写清楚。

- [ ] **Step 5: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src/main/java/com/jiaxian/analytics/pv/PvDailyQueryRepository.java backend/src/test/java/com/jiaxian/analytics/pv/PvDailyQueryRepositoryIT.java
git commit -m "feat: add PV daily and top-N query repository

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 分析服务（编排与校验）

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/pv/PvAnalyticsService.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/pv/PvAnalyticsServiceTest.java`
- Modify: `docs/superpowers/specs/2026-10-04-pv-analytics-design.md`（5.2 的一句话）

- [ ] **Step 1: 写失败的测试**

`PvAnalyticsServiceTest.java`（Mockito 替身 Repository + 固定时钟，今天 = 2026-06-10）：

```java
package com.jiaxian.analytics.pv;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PvAnalyticsServiceTest {

    private static final LocalDate JUNE_10 = LocalDate.of(2026, 6, 10);

    private final PvDailyQueryRepository repository = mock(PvDailyQueryRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-10T08:00:00Z"), ZoneOffset.UTC);
    private final PvAnalyticsService service = new PvAnalyticsService(repository, clock);

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    @Test
    void trendFetchesOneExtraYearAndReturnsOnlyRequestedPeriods() {
        // 请求 2026-03；起点 2026-03-01 往前 1 年 1 周 = 2025-02-22
        when(repository.dailyPv(d(2025, 2, 22), d(2026, 3, 31), PvFilter.NONE)).thenReturn(List.of(
            new DailyPv(d(2025, 3, 10), 100), new DailyPv(d(2026, 2, 15), 50), new DailyPv(d(2026, 3, 10), 150)));

        PvTrend trend = service.trend(Granularity.MONTH, d(2026, 3, 1), d(2026, 3, 31), PvFilter.NONE, 1);

        assertThat(trend.granularity()).isEqualTo(Granularity.MONTH);
        assertThat(trend.from()).isEqualTo(d(2026, 3, 1));
        assertThat(trend.to()).isEqualTo(d(2026, 3, 31));
        assertThat(trend.points()).hasSize(1);
        PvPoint march = trend.points().get(0);
        assertThat(march.periodStart()).isEqualTo(d(2026, 3, 1));
        assertThat(march.pv()).isEqualTo(150);
        assertThat(march.momRate()).isCloseTo(2.0, within(1e-9));   // (150-50)/50
        assertThat(march.yoyRate()).isCloseTo(0.5, within(1e-9));   // (150-100)/100（去年同期来自多取的那一年）
        assertThat(march.complete()).isTrue();

        // 预测基于全部已完成周期：最近 3 个月 0, 50, 150 → ma = 200/3；前 3 个月 0 → slope = ma/3
        assertThat(trend.forecast()).hasSize(1);
        assertThat(trend.forecast().get(0).periodStart()).isEqualTo(d(2026, 4, 1));
        assertThat(trend.forecast().get(0).value()).isCloseTo(800.0 / 9, within(1e-9));
    }

    @Test
    void trendWithToBeyondTodayOnlyQueriesUntilToday() {
        // 起点 2026-06-10 往前 1 年 1 周 = 2025-06-03；to 超过今天，只查到今天
        when(repository.dailyPv(d(2025, 6, 3), JUNE_10, PvFilter.NONE)).thenReturn(List.of(new DailyPv(JUNE_10, 6)));

        PvTrend trend = service.trend(Granularity.DAY, d(2026, 6, 10), d(2026, 12, 31), PvFilter.NONE, 0);

        assertThat(trend.points()).hasSize(1);
        assertThat(trend.points().get(0).pv()).isEqualTo(6);
        assertThat(trend.points().get(0).complete()).isFalse();
        assertThat(trend.forecast()).isEmpty();
    }

    @Test
    void trendForAFutureRangeHasNoPoints() {
        PvTrend trend = service.trend(Granularity.MONTH, d(2026, 7, 1), d(2026, 7, 31), PvFilter.NONE, 1);

        assertThat(trend.points()).isEmpty();
    }

    @Test
    void trendRejectsBadArguments() {
        assertThatThrownBy(() -> service.trend(Granularity.DAY, d(2026, 6, 5), d(2026, 6, 1), PvFilter.NONE, 1))
            .isInstanceOf(InvalidPvQueryException.class).hasMessageContaining("from");
        assertThatThrownBy(() -> service.trend(Granularity.DAY, d(2026, 6, 1), d(2026, 6, 5), PvFilter.NONE, -1))
            .isInstanceOf(InvalidPvQueryException.class).hasMessageContaining("forecastPeriods");
        assertThatThrownBy(() -> service.trend(Granularity.DAY, d(2026, 6, 1), d(2026, 6, 5), PvFilter.NONE, 13))
            .isInstanceOf(InvalidPvQueryException.class).hasMessageContaining("forecastPeriods");
    }

    @Test
    void topDelegatesToRepository() {
        List<PvTopEntry> rows = List.of(new PvTopEntry(7L, "半导体", 3456));
        when(repository.top(PvDimension.CHANNEL, d(2026, 1, 1), d(2026, 3, 31), PvFilter.NONE, 5)).thenReturn(rows);

        assertThat(service.top(PvDimension.CHANNEL, d(2026, 1, 1), d(2026, 3, 31), PvFilter.NONE, 5)).isEqualTo(rows);
        verify(repository).top(PvDimension.CHANNEL, d(2026, 1, 1), d(2026, 3, 31), PvFilter.NONE, 5);
    }

    @Test
    void topRejectsBadArguments() {
        assertThatThrownBy(() -> service.top(PvDimension.CHANNEL, d(2026, 6, 5), d(2026, 6, 1), PvFilter.NONE, 10))
            .isInstanceOf(InvalidPvQueryException.class).hasMessageContaining("from");
        assertThatThrownBy(() -> service.top(PvDimension.CHANNEL, d(2026, 6, 1), d(2026, 6, 5), PvFilter.NONE, 0))
            .isInstanceOf(InvalidPvQueryException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> service.top(PvDimension.CHANNEL, d(2026, 6, 1), d(2026, 6, 5), PvFilter.NONE, 101))
            .isInstanceOf(InvalidPvQueryException.class).hasMessageContaining("limit");
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest=PvAnalyticsServiceTest`
Expected: 编译失败（`PvAnalyticsService` 不存在）。

- [ ] **Step 3: 写实现**

`PvAnalyticsService.java`：

```java
package com.jiaxian.analytics.pv;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** 编排：校验参数 → 多取一年数据 → 分桶补零 → 同环比与预测 → 裁剪成请求范围。 */
@Service
public class PvAnalyticsService {

    static final int MAX_FORECAST_PERIODS = 12;
    static final int MAX_LIMIT = 100;

    private final PvDailyQueryRepository repository;
    private final Clock clock;

    public PvAnalyticsService(PvDailyQueryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public PvTrend trend(Granularity granularity, LocalDate from, LocalDate to,
                         PvFilter filter, int forecastPeriods) {
        requireOrderedRange(from, to);
        if (forecastPeriods < 0 || forecastPeriods > MAX_FORECAST_PERIODS) {
            throw new InvalidPvQueryException("forecastPeriods must be between 0 and " + MAX_FORECAST_PERIODS);
        }

        LocalDate today = LocalDate.now(clock);
        LocalDate visibleFrom = granularity.periodStart(from);
        // 往前多取一年，让范围内最早的点也能算同比；再多一周是为了 WEEK 粒度（52 周 = 364 天）对齐不丢点。
        LocalDate fetchFrom = visibleFrom.minusYears(1).minusWeeks(1);
        LocalDate fetchTo = to.isAfter(today) ? today : to;

        List<DailyPv> daily = fetchFrom.isAfter(fetchTo)
            ? List.of() : repository.dailyPv(fetchFrom, fetchTo, filter);
        List<PvBucket> buckets = PvSeriesBuilder.build(daily, granularity, fetchFrom, to, today);
        PvTrendAnalyzer.Analysis analysis =
            PvTrendAnalyzer.analyze(buckets, granularity, visibleFrom, forecastPeriods);

        return new PvTrend(granularity, from, to, analysis.points(), analysis.forecast());
    }

    public List<PvTopEntry> top(PvDimension dimension, LocalDate from, LocalDate to,
                                PvFilter filter, int limit) {
        requireOrderedRange(from, to);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidPvQueryException("limit must be between 1 and " + MAX_LIMIT);
        }
        return repository.top(dimension, from, to, filter, limit);
    }

    private static void requireOrderedRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new InvalidPvQueryException("from must not be after to");
        }
    }
}
```

同步设计文档：编辑 `docs/superpowers/specs/2026-10-04-pv-analytics-design.md` 5.2 节，把

`- 为了让返回范围内最早的点也能算出同比，服务自动把查询范围再往前多取一年，计算完只返回请求范围内的点。`

改为

`- 为了让返回范围内最早的点也能算出同比，服务自动把查询范围再往前多取一年（起点 = 请求起点所在周期的起点 − 1 年 − 1 周，多出的一周保证 WEEK 粒度 52 周前的周期也在数据里），计算完只返回请求范围内的点。`

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn test -Dtest=PvAnalyticsServiceTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src/main/java/com/jiaxian/analytics/pv/PvAnalyticsService.java backend/src/test/java/com/jiaxian/analytics/pv/PvAnalyticsServiceTest.java docs/superpowers/specs/2026-10-04-pv-analytics-design.md
git commit -m "feat: add PV analytics service orchestrating trend and top-N

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 8: HTTP 接口与 400 处理

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/pv/PvAnalyticsController.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/pv/PvAdminController.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/pv/PvExceptionHandler.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/pv/PvAnalyticsControllerIT.java`

- [ ] **Step 1: 写失败的测试**

`PvAnalyticsControllerIT.java`（不带 `@Transactional`，走真实 HTTP；只用不会命中真实数据的 2019 日期和不存在的 id）：

```java
package com.jiaxian.analytics.pv;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PvAnalyticsControllerIT {

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void assertBadRequest(String path, String expectedMessagePart) {
        ResponseEntity<String> response = rest.getForEntity(url(path), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains(expectedMessagePart);
    }

    @Test
    void trendReturnsStructuredSeriesForAnUnknownChannel() {
        ResponseEntity<JsonNode> response = rest.getForEntity(
            url("/api/analytics/pv/trend?granularity=DAY&from=2019-06-01&to=2019-06-03&channelId=-1&forecastPeriods=0"),
            JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body.get("granularity").asText()).isEqualTo("DAY");
        assertThat(body.get("from").asText()).isEqualTo("2019-06-01");
        assertThat(body.get("to").asText()).isEqualTo("2019-06-03");
        assertThat(body.get("points")).hasSize(3);
        assertThat(body.get("points").get(0).get("periodStart").asText()).isEqualTo("2019-06-01");
        assertThat(body.get("points").get(0).get("pv").asLong()).isZero();
        assertThat(body.get("points").get(0).get("complete").asBoolean()).isTrue();
        assertThat(body.get("forecast")).isEmpty();
    }

    @Test
    void topReturnsEmptyArrayForAnUnknownChannelFilter() {
        ResponseEntity<JsonNode> response = rest.getForEntity(
            url("/api/analytics/pv/top?dimension=ACCOUNT&from=2019-06-01&to=2019-06-30&channelId=-1"),
            JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().isArray()).isTrue();
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void trendRejectsBadParameters() {
        assertBadRequest("/api/analytics/pv/trend?granularity=HOURLY&from=2019-06-01&to=2019-06-03", "granularity");
        assertBadRequest("/api/analytics/pv/trend?granularity=DAY&from=06/01/2019&to=2019-06-03", "from");
        assertBadRequest("/api/analytics/pv/trend?granularity=DAY&from=2019-06-05&to=2019-06-03", "from must not be after to");
        assertBadRequest("/api/analytics/pv/trend?granularity=DAY&from=2019-06-01&to=2019-06-03&forecastPeriods=13", "forecastPeriods");
        assertBadRequest("/api/analytics/pv/trend?granularity=DAY&from=2019-06-01&to=2019-06-03&contentType=BLOG", "contentType");
        assertBadRequest("/api/analytics/pv/trend?granularity=DAY&from=2019-06-01", "to");
    }

    @Test
    void topRejectsBadParameters() {
        assertBadRequest("/api/analytics/pv/top?dimension=TEAM&from=2019-06-01&to=2019-06-30", "dimension");
        assertBadRequest("/api/analytics/pv/top?dimension=CHANNEL&from=2019-06-01&to=2019-06-30&limit=0", "limit");
        assertBadRequest("/api/analytics/pv/top?dimension=CHANNEL&from=2019-06-01&to=2019-06-30&limit=101", "limit");
        assertBadRequest("/api/analytics/pv/top?dimension=CHANNEL&from=2019-06-30&to=2019-06-01", "from must not be after to");
    }

    @Test
    void rebuildEndpointReturnsRowsWritten() {
        ResponseEntity<JsonNode> response = rest.postForEntity(
            url("/api/admin/analytics/pv/rebuild?from=2019-06-01&to=2019-06-02"), null, JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("from").asText()).isEqualTo("2019-06-01");
        assertThat(response.getBody().get("to").asText()).isEqualTo("2019-06-02");
        assertThat(response.getBody().get("rowsWritten").asInt()).isZero();   // 2019 年没有任何浏览记录
    }

    @Test
    void rebuildEndpointRejectsReversedRange() {
        ResponseEntity<String> response = rest.postForEntity(
            url("/api/admin/analytics/pv/rebuild?from=2019-06-05&to=2019-06-01"), null, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("from must not be after to");
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest=PvAnalyticsControllerIT`
Expected: 测试失败（接口还不存在，返回 404）。

- [ ] **Step 3: 写实现**

`PvExceptionHandler.java`：

```java
package com.jiaxian.analytics.pv;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 只作用于 PV 接口：参数问题统一返回 400 + 一句说明原因的文字。 */
@RestControllerAdvice(assignableTypes = {PvAnalyticsController.class, PvAdminController.class})
public class PvExceptionHandler {

    @ExceptionHandler(InvalidPvQueryException.class)
    public ResponseEntity<String> invalidQuery(InvalidPvQueryException e) {
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

`PvAnalyticsController.java`：

```java
package com.jiaxian.analytics.pv;

import com.jiaxian.analytics.domain.ContentType;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/analytics/pv")
public class PvAnalyticsController {

    private final PvAnalyticsService service;

    public PvAnalyticsController(PvAnalyticsService service) {
        this.service = service;
    }

    /** PV 趋势（含环比、同比、预测）。枚举参数区分大小写（大写）。 */
    @GetMapping("/trend")
    public PvTrend trend(
        @RequestParam Granularity granularity,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) Long channelId,
        @RequestParam(required = false) Long accountId,
        @RequestParam(required = false) Long employeeId,
        @RequestParam(required = false) ContentType contentType,
        @RequestParam(defaultValue = "1") int forecastPeriods) {
        return service.trend(granularity, from, to,
            new PvFilter(channelId, accountId, employeeId, contentType), forecastPeriods);
    }

    /**
     * PV Top N。EMPLOYEE（博主）维度下，一篇内容有多位共同作者时每位作者都记完整 PV，
     * 所以所有博主的 PV 之和会大于全站总 PV。
     */
    @GetMapping("/top")
    public List<PvTopEntry> top(
        @RequestParam PvDimension dimension,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue = "10") int limit,
        @RequestParam(required = false) Long channelId,
        @RequestParam(required = false) Long accountId,
        @RequestParam(required = false) Long employeeId,
        @RequestParam(required = false) ContentType contentType) {
        return service.top(dimension, from, to,
            new PvFilter(channelId, accountId, employeeId, contentType), limit);
    }
}
```

`PvAdminController.java`：

```java
package com.jiaxian.analytics.pv;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin/analytics/pv")
public class PvAdminController {

    public record RebuildResult(LocalDate from, LocalDate to, int rowsWritten) {
    }

    private final PvSummaryService summaryService;

    public PvAdminController(PvSummaryService summaryService) {
        this.summaryService = summaryService;
    }

    /** from/to 都可省略；全省略表示重算全部历史（首次灌数据用）。 */
    @PostMapping("/rebuild")
    public RebuildResult rebuild(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return new RebuildResult(from, to, summaryService.rebuild(from, to));
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn test -Dtest=PvAnalyticsControllerIT`
Expected: `Tests run: 6, Failures: 0, Errors: 0`

如果 `trendReturnsStructuredSeries…` 里 `from` 被序列化成数组 `[2019,6,1]`，说明 Jackson 的日期序列化没有走 ISO 字符串：在 `application.yml` 加 `spring.jackson.serialization.write-dates-as-timestamps: false` 再跑（Spring Boot 默认已关闭，通常不会出现）。

- [ ] **Step 5: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src/main/java/com/jiaxian/analytics/pv backend/src/test/java/com/jiaxian/analytics/pv/PvAnalyticsControllerIT.java
git commit -m "feat: add PV trend, top-N and rebuild HTTP endpoints

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 9: 端到端一致性测试

**Files:**
- Test: `backend/src/test/java/com/jiaxian/analytics/pv/PvAnalyticsEndToEndIT.java`

核心断言：历史回填 → 重算 → 通过 HTTP 取趋势和 Top N，总和与 `pv_log` 的行数完全一致；再次回填不会撞汇总表外键（`ON DELETE CASCADE`）。

> 说明：回填的浏览时间是"内容发布时间 + 最多 30 天"，所以 `historyEnd` 取 2026-03-01，浏览最晚到 2026-03-31，早于今天；`historyStart` 取 2025-01-01，这样 2026 年 1–3 月才有同比。

- [ ] **Step 1: 写测试**

`PvAnalyticsEndToEndIT.java`：

```java
package com.jiaxian.analytics.pv;

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

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 OpenGauss + 真实 HTTP：回填 → 重算 → 趋势 / Top N，核心断言是"汇总总和 = pv_log 行数"。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PvAnalyticsEndToEndIT {

    @Autowired private HistoricalBackfillService backfillService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void backfill() {
        backfillService.runBackfill(SimulationConfig.builder()
            .batchId("pv-e2e")
            .employeeCount(30)
            .accountCount(12)
            .teamAccountRatio(0.25)
            .contentCount(60)
            .historyStart(LocalDate.of(2025, 1, 1))
            .historyEnd(LocalDate.of(2026, 3, 1))
            .randomSeed(21)
            .build());
    }

    private JsonNode get(String path) {
        ResponseEntity<JsonNode> response = rest.getForEntity(url(path), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    private long sumPv(JsonNode rows) {
        long total = 0;
        for (JsonNode row : rows) {
            total += row.get("pv").asLong();
        }
        return total;
    }

    @Test
    void summaryTrendAndTopAllAgreeWithPvLog() {
        backfill();   // 汇总表里如果有之前的数据，这里也会验证 ON DELETE CASCADE 让回填不报错
        long pvLogRows = jdbc.queryForObject("SELECT COUNT(*) FROM pv_log", Long.class);
        assertThat(pvLogRows).isGreaterThan(0);

        ResponseEntity<JsonNode> rebuild = rest.postForEntity(url("/api/admin/analytics/pv/rebuild"), null, JsonNode.class);
        assertThat(rebuild.getStatusCode().value()).isEqualTo(200);
        assertThat(rebuild.getBody().get("rowsWritten").asInt()).isGreaterThan(0);

        Long summaryTotal = jdbc.queryForObject("SELECT SUM(pv_count) FROM pv_summary_daily", Long.class);
        assertThat(summaryTotal).isEqualTo(pvLogRows);

        // 趋势：年、月两种粒度的点加起来都等于总浏览数
        String range = "from=2025-01-01&to=2026-12-31&forecastPeriods=0";
        assertThat(sumPv(get("/api/analytics/pv/trend?granularity=YEAR&" + range).get("points"))).isEqualTo(pvLogRows);
        assertThat(sumPv(get("/api/analytics/pv/trend?granularity=MONTH&" + range).get("points"))).isEqualTo(pvLogRows);

        // Top N：频道/账号/内容类型各内容只归属一个，总和等于总浏览数；博主有合著，总和只会更大
        String topRange = "from=2025-01-01&to=2026-12-31&limit=100";
        assertThat(sumPv(get("/api/analytics/pv/top?dimension=CHANNEL&" + topRange))).isEqualTo(pvLogRows);
        assertThat(sumPv(get("/api/analytics/pv/top?dimension=ACCOUNT&" + topRange))).isEqualTo(pvLogRows);
        assertThat(sumPv(get("/api/analytics/pv/top?dimension=CONTENT_TYPE&" + topRange))).isEqualTo(pvLogRows);
        assertThat(sumPv(get("/api/analytics/pv/top?dimension=EMPLOYEE&" + topRange))).isGreaterThanOrEqualTo(pvLogRows);

        // 同比：2025 年有数据，2026 年 1–3 月至少有一个月能算出同比
        JsonNode points = get("/api/analytics/pv/trend?granularity=MONTH&from=2026-01-01&to=2026-03-31&forecastPeriods=0")
            .get("points");
        boolean anyYoy = false;
        for (JsonNode point : points) {
            anyYoy |= !point.get("yoyRate").isNull();
        }
        assertThat(anyYoy).isTrue();

        // 再次回填会 deleteAll() 全部内容：汇总表的行必须随内容级联删除
        backfill();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pv_summary_daily", Long.class)).isZero();
    }
}
```

- [ ] **Step 2: 运行**

Run: `cd backend && mvn test -Dtest=PvAnalyticsEndToEndIT`
Expected: `Tests run: 1, Failures: 0, Errors: 0`

失败时的排查顺序：
1. 汇总总和 ≠ `pv_log` 行数：用 gsql 检查 `SELECT count(*), count(DISTINCT date_trunc('day', view_time)) FROM pv_log`，确认 `date_trunc` 分组没问题。
2. 年/月趋势的和 ≠ 总数：检查是否有浏览时间晚于今天或早于 `fetchFrom`（回填不会产生这种数据，除非 `historyEnd` 被改动）。
3. `anyYoy` 为假：可能是这个随机种子下 2025 年 1–3 月恰好没有数据；把 `randomSeed` 换一个值或把 `contentCount` 调大后重试，并在测试里注明原因。

- [ ] **Step 3: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src/test/java/com/jiaxian/analytics/pv/PvAnalyticsEndToEndIT.java
git commit -m "test: add end-to-end PV analytics consistency test

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 10: 手工验证与收尾

**Files:**
- Modify: `docs/superpowers/specs/2026-10-04-pv-analytics-design.md`（状态行）

- [ ] **Step 1: 启动应用并回填两年数据**

```bash
cd backend && mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8093
```

Expected: 日志出现 `Started AnalyticsApplication`，没有 schema 报错。另开一个终端：

```bash
curl -s -X POST localhost:8093/api/admin/simulator/backfill -H 'Content-Type: application/json' \
  -d '{"batchId":"manual-pv","employeeCount":50,"accountCount":20,"teamAccountRatio":0.25,"contentCount":300,"historyStart":"2024-10-01","historyEnd":"2026-09-01","randomSeed":7}'
```

Expected: `Backfill completed for batch: manual-pv`

- [ ] **Step 2: 重算并调用接口**

```bash
curl -s -X POST localhost:8093/api/admin/analytics/pv/rebuild
curl -s 'localhost:8093/api/analytics/pv/trend?granularity=MONTH&from=2026-01-01&to=2026-10-04&forecastPeriods=3'
curl -s 'localhost:8093/api/analytics/pv/trend?granularity=WEEK&from=2026-08-01&to=2026-10-04&contentType=VIDEO'
curl -s 'localhost:8093/api/analytics/pv/top?dimension=EMPLOYEE&from=2026-01-01&to=2026-10-04&limit=5'
curl -s -i 'localhost:8093/api/analytics/pv/trend?granularity=HOURLY&from=2026-01-01&to=2026-02-01'
```

Expected：
- rebuild 返回 `{"from":null,"to":null,"rowsWritten":N}`，N > 0；
- 月趋势里 2026 年各月 `yoyRate` 有数值（2025 年同月有数据）、最后一个月 `complete:false`、`forecast` 有 3 个点且 `lower ≤ value ≤ upper`；
- 周趋势只含视频内容，每个点的 `periodStart` 都是周一；
- Top 5 员工按 PV 降序；
- 最后一条返回 `HTTP/1.1 400`，响应体是文字说明。

人工看一眼：趋势里"进行中"的月份是否被标成未完成、预测第 1 期是否正好是这个进行中的月份（符合设计 5.4）。

- [ ] **Step 3: 关闭应用**

```bash
lsof -i :8093
kill <PID>
```

Expected: 端口释放。

- [ ] **Step 4: 更新设计文档状态并提交**

把 `docs/superpowers/specs/2026-10-04-pv-analytics-design.md` 开头的 `- 状态：已通过讨论确认，待转入实施计划` 改为 `- 状态：已实现（实施计划见 [PV 多维度聚合与预测](../plans/2026-10-04-pv-analytics.md)）`。

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add docs/superpowers/specs/2026-10-04-pv-analytics-design.md
git commit -m "docs: mark PV analytics spec as implemented

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

之后按 `superpowers:finishing-a-development-branch` 收尾（在 `main` 上直接工作，已获用户同意）；推送到 GitHub 只在用户要求时进行。

---

## Self-Review

**1. 设计文档覆盖：**
- 第 3 节数据模型（含 `ON DELETE CASCADE`、`date_trunc`、日期读取方式）→ Task 4（表、`PvSummaryService`、级联测试、`date_trunc` 分组测试）。
- 第 4 节重算（先删后插、空边界、返回行数、事务）→ Task 4；定时 + 属性开关 → Task 5；手动接口 → Task 8。
- 第 5 节趋势：分桶补零周期扩展 → Task 2；同环比 → Task 3；未完成周期不参与预测 → Task 3（`forecastUsesMovingAveragePlusDrift` 里的进行中周期）+ Task 2（`complete` 标志）；预测公式、窗口、σ、下限截断、点不足不预测 → Task 3；多取一年 → Task 7；`forecastPeriods` 0~12 → Task 7/8。
- 第 6 节 Top N（四个维度、排序、`CONTENT_TYPE` 的 null id、`limit` 字面量、合著满额、重复作者行去重）→ Task 6/8。
- 第 7 节组件划分 → 文件结构表，8 个组件全部有任务。
- 第 8 节错误处理（400 文本、不存在的 id 返回空/全 0）→ Task 7/8。
- 第 9 节测试策略：纯逻辑单测（Task 1–3）、对真实库的 IT（Task 4、6）、接口测试（Task 8）、端到端一致性（Task 9）、手工验证 2 年回填（Task 10）。

**2. 占位符扫描：** 无 TBD/TODO。Task 7 的测试里有一处我已在 Step 1 的提示和 Step 2 里显式修正的手算笔误（`2025-5-25` → `2025-6-3`），执行时按 Step 2 的代码替换，不留遗留问题。

**3. 类型一致性：** `Granularity`（`periodStart`/`periodEnd`/`nextPeriodStart`/`sameLastYear`/`forecastWindow`）在 Task 1 定义，Task 2/3/7 使用名称一致；`PvBucket(periodStart, pv, complete)`、`DailyPv(date, pv)`、`PvPoint`、`ForecastPoint`、`PvTrend(granularity, from, to, points, forecast)`、`PvTopEntry(id, name, pv)`、`PvFilter(channelId, accountId, employeeId, contentType)` + `PvFilter.NONE` 在 Task 1 定义，后续一致；`PvDailyQueryRepository.dailyPv(from, to, filter)` / `top(dimension, from, to, filter, limit)` 在 Task 6 定义，Task 7 的 Mockito 桩按同样的签名；`PvTrendAnalyzer.analyze(buckets, granularity, visibleFrom, forecastPeriods)` 返回 `Analysis(points, forecast)`，Task 3 定义、Task 7 使用；`PvSummaryService.rebuild(from, to)` 在 Task 4 定义，Task 5/8 使用；`InvalidPvQueryException` 在 Task 1 定义，Task 4/7/8 使用，Repository 里的越界保护抛 `IllegalArgumentException`（编程错误，不映射为 400，因为 Service 已先校验）。
