# KOL 分层与流失预警 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付 KOL 管理后端：每周计算 TOP N 名单的 RFM 打分、分层标签和流失预警，建议文案库（可维护），名单查询接口；并给模拟器加"博主画像"，让每个分层和预警都能在模拟数据里真实出现并被验证。

**Architecture:** 打分、预警、分层都是纯 Java（`RfmScorer`、`ChurnDetector`、`SegmentClassifier`），用手算数字做单元测试；指标取数（`KolMetricsRepository`）和结果落库（`KolRfmRepository`）用 `JdbcTemplate`，无 JPA 实体；`KolRebuildService` 在一个事务里先删后插，重算幂等，并按周补历史。模拟画像是 `(员工数, 种子)` 的纯函数，只控制登录行为，默认关闭。

**Tech Stack:** Spring Boot 3.2.5 (Java 17), JdbcTemplate, OpenGauss 5.0.0（A 兼容模式）, JUnit 5 + Mockito + AssertJ, Spring `@Scheduled`、`@ConfigurationProperties`。

**设计文档：** `docs/superpowers/specs/2026-10-04-kol-segmentation-design.md`（先读它，尤其是第 3、4 节的口径和分层顺序）。

---

## 必读：约定与踩坑记录

沿用 Plan 3、Plan 4（`docs/superpowers/plans/2026-10-04-pv-analytics.md`、`…-leaderboard.md`）的"约定与踩坑记录"。与本计划直接相关的：

1. **日期参数一律绑定 `java.sql.Timestamp`**；`DATE` 列在 A 兼容模式下实际是 `timestamp`，读取用 `getTimestamp(…).toLocalDateTime().toLocalDate()`。**不要** `CAST(… AS DATE)`。
2. **`LIMIT`/`OFFSET` 必须是校验过的整数字面量**，不能绑定参数。
3. **`SELECT ?` 这种裸参数放在选择列表里 OpenGauss 推断不出类型**；批量插入时用 `batchUpdate(sql, 参数列表, 参数类型数组)` 显式给类型，避免 `null` 参数的类型问题。
4. **`@Repository` 会把 `IllegalArgumentException` 包成 `InvalidDataAccessApiUsageException`**。参数校验放在 Service 里抛自己的异常。
5. **`@Transactional` 自调用不生效**：`rebuildWeeks` 内部调用 `rebuild` 时，事务靠 `rebuildWeeks` 自己的注解提供。
6. **集成测试 `*IT` 连真实 OpenGauss**，优先 `@Transactional`；夹具日期统一用 **2019 年**，名字用"KOL测试"前缀；`HistoricalBackfillService.runBackfill()` 会 `deleteAll()` 全部员工，所以三张新表对员工的外键都要 `ON DELETE CASCADE`。
7. **每个任务完成后都跑完整 `cd backend && mvn test`**；本计划不改 `pom.xml`。
8. 端口 8080 被无关进程占用——手工启动应用时用 `--server.port=8093`，用完 `pkill -f spring-boot:run` 并确认 `lsof -i :8093` 为空。
9. `data.sql` 每次启动都会执行，种子必须幂等：`ON DUPLICATE KEY UPDATE <非主键列> = <非主键列>`。
10. 本计划里"画像参数"（登录概率、截止天数等）是经验值：Task 8 的端到端测试若有某个画像没有落入预期分层，只调 `PersonaLoginPlanner` 里的常量，并把调整写进提交说明。

## 文件结构

新建（`backend/src/main/java/com/jiaxian/analytics/kol/`，测试在 `backend/src/test/java/com/jiaxian/analytics/kol/`，包名 `com.jiaxian.analytics.kol`）：

| 文件 | 职责 |
|---|---|
| `Segment.java` | 分层枚举（含中文名） |
| `KolProperties.java` | `analytics.kol.*` 配置绑定 |
| `BloggerMetrics.java`、`RfmScores.java`、`ChurnSignal.java`、`KolRebuildResult.java` | 记录类 |
| `InvalidKolQueryException.java`、`PlaybookNotFoundException.java`、`PlaybookConflictException.java` | 400 / 404 / 409 |
| `RfmScorer.java` | 纯逻辑：五分位打分 |
| `ChurnDetector.java` | 纯逻辑：流失预警判定 |
| `SegmentClassifier.java` | 纯逻辑：分层规则 |
| `PlaybookAlertStatus.java`、`PlaybookEntry.java`、`PlaybookRequest.java`、`PlaybookLookup.java` | 文案库记录与查找 |
| `PlaybookRepository.java`、`PlaybookService.java` | 文案库读写 |
| `KolMetricsRepository.java` | 名单与各窗口指标取数 |
| `KolRfmRepository.java` | 每周结果读写 |
| `KolRebuildService.java`、`KolScheduler.java` | 重算与定时 |
| `BloggerRow.java`、`BloggerItem.java`、`BloggerList.java` | 名单查询的记录类 |
| `BloggerListRepository.java`、`BloggerListService.java` | 名单查询 |
| `BloggerController.java`、`KolAdminController.java`、`PlaybookController.java`、`KolExceptionHandler.java` | HTTP 接口与错误映射 |

模拟器（`com.jiaxian.analytics.simulator`）新建：`BloggerPersona.java`、`PersonaAssigner.java`、`PersonaLoginPlanner.java`；修改：`SimulationConfig.java`、`HistoricalBackfillService.java`、`SimulatorController.java`。

修改：`backend/src/main/resources/schema.sql`、`data.sql`、`application.yml`，`backend/src/test/resources/application.properties`，母设计文档（同步两处差异）。

测试：`RfmScorerTest`、`ChurnDetectorTest`、`SegmentClassifierTest`、`KolTestFixtures`、`PlaybookServiceIT`、`KolMetricsRepositoryIT`、`KolRebuildServiceIT`、`KolSchedulerTest`、`BloggerListServiceTest`、`BloggerListServiceIT`、`KolControllerIT`、`KolEndToEndIT`；模拟器侧 `PersonaAssignerTest`、`PersonaLoginPlannerTest`、`HistoricalBackfillPersonaIT`。

---

### Task 1: 分层枚举、配置、记录类与五分位打分

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/kol/{Segment,KolProperties,BloggerMetrics,RfmScores,ChurnSignal,KolRebuildResult,InvalidKolQueryException,PlaybookNotFoundException,PlaybookConflictException,RfmScorer}.java`
- Modify: `backend/src/main/resources/application.yml`、`backend/src/test/resources/application.properties`
- Test: `backend/src/test/java/com/jiaxian/analytics/kol/RfmScorerTest.java`

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/jiaxian/analytics/kol/RfmScorerTest.java`：

```java
package com.jiaxian.analytics.kol;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RfmScorerTest {

    private static BloggerMetrics metrics(long id, Integer recencyDays, int frequency, long monetary) {
        return new BloggerMetrics(id, (int) id, 0, recencyDays, frequency, monetary, 0, 0, 0, 0, 0);
    }

    @Test
    void emptyPopulationYieldsNoScores() {
        assertThat(RfmScorer.score(List.of())).isEmpty();
    }

    @Test
    void tenPeopleSpreadEvenlyAcrossTheFiveScores() {
        // id i：F = i-1（越大越好），R = 10-i 天（越小越好），M = (10-i)*100。
        // 分数 = 1 + (严格更差的人数 * 5) / 10：更差的人数 0..9 → 1,1,2,2,3,3,4,4,5,5
        List<BloggerMetrics> people = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            people.add(metrics(i, 10 - i, i - 1, (10 - i) * 100L));
        }

        Map<Long, RfmScores> scores = RfmScorer.score(people);

        int[] expectedFandR = {1, 1, 2, 2, 3, 3, 4, 4, 5, 5};
        int[] expectedM = {5, 5, 4, 4, 3, 3, 2, 2, 1, 1};
        for (int i = 1; i <= 10; i++) {
            RfmScores s = scores.get((long) i);
            // R：id 越大天数越小 → 越近 → 分越高，和 F 的模式相同
            assertThat(s.r()).as("R of %d", i).isEqualTo(expectedFandR[i - 1]);
            assertThat(s.f()).as("F of %d", i).isEqualTo(expectedFandR[i - 1]);
            assertThat(s.m()).as("M of %d", i).isEqualTo(expectedM[i - 1]);
        }
    }

    @Test
    void tiesShareTheSameScoreAndMissingRecencyIsTheWorst() {
        // 4 人：F = 1,2,2,3；R = 0,5,5,空；M 全相同
        List<BloggerMetrics> people = List.of(
            metrics(1, 0, 1, 50), metrics(2, 5, 2, 50), metrics(3, 5, 2, 50), metrics(4, null, 3, 50));

        Map<Long, RfmScores> scores = RfmScorer.score(people);

        // F：比自己小的人数 0,1,1,3 → 1 + (c*5)/4 = 1,2,2,4
        assertThat(scores.get(1L).f()).isEqualTo(1);
        assertThat(scores.get(2L).f()).isEqualTo(2);
        assertThat(scores.get(3L).f()).isEqualTo(2);
        assertThat(scores.get(4L).f()).isEqualTo(4);
        // R：比自己更差（天数更大）的人数 3,1,1,0 → 4,2,2,1
        assertThat(scores.get(1L).r()).isEqualTo(4);
        assertThat(scores.get(2L).r()).isEqualTo(2);
        assertThat(scores.get(3L).r()).isEqualTo(2);
        assertThat(scores.get(4L).r()).isEqualTo(1);
        // M：全员相同 → 全员 1 分
        assertThat(scores.values()).allSatisfy(s -> assertThat(s.m()).isEqualTo(1));
    }

    @Test
    void aSinglePersonScoresOne() {
        RfmScores only = RfmScorer.score(List.of(metrics(1, 3, 10, 999))).get(1L);

        assertThat(only).isEqualTo(new RfmScores(1, 1, 1));
    }

    @Test
    void averageIsTheMeanOfTheThreeScores() {
        assertThat(new RfmScores(4, 4, 2).average()).isEqualTo(10.0 / 3);
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest=RfmScorerTest`
Expected: 编译失败（`BloggerMetrics` 等不存在）。

- [ ] **Step 3: 写实现**

`backend/src/main/java/com/jiaxian/analytics/kol/Segment.java`：

```java
package com.jiaxian.analytics.kol;

/** 博主分层标签。 */
public enum Segment {
    STAR_KOL("明星KOL"),
    STEADY("稳定贡献者"),
    RISING("成长潜力新人"),
    CHURN_RISK("流失预警"),
    DORMANT("沉寂用户"),
    OBSERVE("观察中");

    private final String displayName;

    Segment(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/KolProperties.java`：

```java
package com.jiaxian.analytics.kol;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** analytics.kol.* 配置，修改后重启生效。 */
@Component
@ConfigurationProperties(prefix = "analytics.kol")
@Getter
@Setter
public class KolProperties {

    /** 名单人数上限。 */
    private int topN = 1000;
    /** R/F/M 的统计窗口天数。 */
    private int windowDays = 90;
    /** 流失预警的"近期"天数（同时也是成长趋势比较的天数）。 */
    private int recentDays = 30;
    /** 流失预警的基线天数（紧挨在近期之前）。 */
    private int baselineDays = 90;
    /** 近期日均相对基线日均下降超过这个比例就触发预警。 */
    private double churnDropThreshold = 0.5;
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/BloggerMetrics.java`：

```java
package com.jiaxian.analytics.kol;

/**
 * 名单内一位博主在统计日的各项原始指标。
 *
 * @param rank                名单内名次（1 起，不并列）
 * @param rollingPoints12m    近 12 个月积分
 * @param recencyDays         距最近一次发帖/评论/登录的天数；一次都没有则为 null
 * @param frequency           近 90 天发帖+评论+登录次数
 * @param monetary            近 90 天积分
 * @param recentActivity      近期（默认 30 天）活跃次数
 * @param baselineActivity    基线期（默认 90 天）活跃次数
 * @param recentPoints        近期积分
 * @param baselinePoints      基线期积分
 * @param previousRecentPoints 上一个近期（紧挨在近期之前、长度相同）的积分
 */
public record BloggerMetrics(long employeeId, int rank, long rollingPoints12m, Integer recencyDays,
                             int frequency, long monetary,
                             long recentActivity, long baselineActivity,
                             long recentPoints, long baselinePoints, long previousRecentPoints) {
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/RfmScores.java`：

```java
package com.jiaxian.analytics.kol;

/** R/F/M 三个 1~5 分。 */
public record RfmScores(int r, int f, int m) {

    public double average() {
        return (r + f + m) / 3.0;
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/ChurnSignal.java`：

```java
package com.jiaxian.analytics.kol;

/** 一次触发的流失预警：哪个指标、近期日均、基线日均、下降比例。 */
public record ChurnSignal(Metric metric, double recentDaily, double baselineDaily, double dropRate) {

    public enum Metric { ACTIVITY, POINTS }
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/KolRebuildResult.java`：

```java
package com.jiaxian.analytics.kol;

import java.time.LocalDate;

/** 一次重算的结果：统计日、名单人数、触发预警的人数。 */
public record KolRebuildResult(LocalDate asOf, int population, int alerts) {
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/InvalidKolQueryException.java`：

```java
package com.jiaxian.analytics.kol;

/** 请求参数非法，对应 HTTP 400。 */
public class InvalidKolQueryException extends RuntimeException {

    public InvalidKolQueryException(String message) {
        super(message);
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/PlaybookNotFoundException.java`：

```java
package com.jiaxian.analytics.kol;

/** 建议文案不存在，对应 HTTP 404。 */
public class PlaybookNotFoundException extends RuntimeException {

    public PlaybookNotFoundException(long id) {
        super("Playbook entry " + id + " does not exist");
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/PlaybookConflictException.java`：

```java
package com.jiaxian.analytics.kol;

/** (分层, 预警状态) 已有文案，对应 HTTP 409。 */
public class PlaybookConflictException extends RuntimeException {

    public PlaybookConflictException(String message) {
        super(message);
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/RfmScorer.java`：

```java
package com.jiaxian.analytics.kol;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯逻辑：对名单内的人做五分位打分（1~5）。
 *
 * <p>F、M：分数 = 1 + (严格小于自己的人数 × 5) / 总人数（整除）；
 * R：天数越小越好，分数 = 1 + (严格大于自己的人数 × 5) / 总人数，没有任何活动记录视为无穷大。
 * 数值相同的人分数相同；全员数值相同则全员 1 分。
 */
public final class RfmScorer {

    private RfmScorer() {
    }

    public static Map<Long, RfmScores> score(List<BloggerMetrics> population) {
        int n = population.size();
        Map<Long, RfmScores> scores = new HashMap<>();
        for (BloggerMetrics person : population) {
            int fewerFrequency = 0;
            int fewerMonetary = 0;
            int laterRecency = 0;
            for (BloggerMetrics other : population) {
                if (other.frequency() < person.frequency()) {
                    fewerFrequency++;
                }
                if (other.monetary() < person.monetary()) {
                    fewerMonetary++;
                }
                if (recencyKey(other) > recencyKey(person)) {
                    laterRecency++;
                }
            }
            scores.put(person.employeeId(),
                new RfmScores(quintile(laterRecency, n), quintile(fewerFrequency, n), quintile(fewerMonetary, n)));
        }
        return scores;
    }

    private static int recencyKey(BloggerMetrics metrics) {
        return metrics.recencyDays() == null ? Integer.MAX_VALUE : metrics.recencyDays();
    }

    private static int quintile(int worseCount, int total) {
        return Math.min(5, 1 + (worseCount * 5) / total);
    }
}
```

`backend/src/main/resources/application.yml` 末尾追加（与 `realtime:` 同级的顶层键）：

```yaml

analytics:
  kol:
    top-n: 1000
    window-days: 90
    recent-days: 30
    baseline-days: 90
    churn-drop-threshold: 0.5
    rebuild-cron: "0 0 1 * * MON"
    schedule:
      enabled: true
```

`backend/src/test/resources/application.properties` 末尾追加一行：

```properties
analytics.kol.schedule.enabled=false
```

> 说明：`analytics:` 作为顶层键，不要与 yml 里已有的键冲突（现有顶层键是 `spring`、`realtime`、`server`）；Plan 3、4 的 `analytics.pv.*`、`analytics.leaderboard.*` 只在测试 properties 里出现，没有写进 `application.yml`。

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn test -Dtest=RfmScorerTest`
Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`（Spring 上下文能启动，说明 `KolProperties` 绑定正常）。

```bash
git add backend/src
git commit -m "feat: add KOL segment enum, properties, records and RFM scorer

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 流失预警与分层规则（纯逻辑）

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/kol/ChurnDetector.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/kol/SegmentClassifier.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/kol/ChurnDetectorTest.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/kol/SegmentClassifierTest.java`

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/jiaxian/analytics/kol/ChurnDetectorTest.java`：

```java
package com.jiaxian.analytics.kol;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ChurnDetectorTest {

    private static final int RECENT_DAYS = 30;
    private static final int BASELINE_DAYS = 90;
    private static final double THRESHOLD = 0.5;

    private static BloggerMetrics metrics(long recentActivity, long baselineActivity,
                                          long recentPoints, long baselinePoints) {
        return new BloggerMetrics(1, 1, 0, 0, 0, 0,
            recentActivity, baselineActivity, recentPoints, baselinePoints, 0);
    }

    private static Optional<ChurnSignal> detect(BloggerMetrics m) {
        return ChurnDetector.detect(m, RECENT_DAYS, BASELINE_DAYS, THRESHOLD);
    }

    @Test
    void activityDropOverThresholdTriggers() {
        // 基线 180 次 / 90 天 = 2.0 次/天；近期 15 次 / 30 天 = 0.5 次/天 → 下降 0.75；积分持平不触发
        ChurnSignal signal = detect(metrics(15, 180, 300, 900)).orElseThrow();

        assertThat(signal.metric()).isEqualTo(ChurnSignal.Metric.ACTIVITY);
        assertThat(signal.recentDaily()).isCloseTo(0.5, within(1e-9));
        assertThat(signal.baselineDaily()).isCloseTo(2.0, within(1e-9));
        assertThat(signal.dropRate()).isCloseTo(0.75, within(1e-9));
    }

    @Test
    void dropExactlyAtTheThresholdDoesNotTrigger() {
        // 基线 90 次 / 90 天 = 1/天；近期 15 次 / 30 天 = 0.5/天 → 下降恰好 0.5（要求严格大于）
        assertThat(detect(metrics(15, 90, 300, 900))).isEmpty();
    }

    @Test
    void dropJustOverTheThresholdTriggers() {
        // 近期 14 次 / 30 天 = 0.4667/天 → 下降 0.5333
        assertThat(detect(metrics(14, 90, 300, 900))).isPresent();
    }

    @Test
    void zeroBaselineNeverTriggers() {
        assertThat(detect(metrics(0, 0, 0, 0))).isEmpty();
        assertThat(detect(metrics(5, 0, 50, 0))).isEmpty();
    }

    @Test
    void growthDoesNotTrigger() {
        assertThat(detect(metrics(90, 90, 900, 900))).isEmpty();
    }

    @Test
    void pointsDropAloneTriggersWithThePointsMetric() {
        // 活跃次数持平（近期 30/30=1，基线 90/90=1）；积分 基线 9000/90=100/天，近期 600/30=20/天 → 下降 0.8
        ChurnSignal signal = detect(metrics(30, 90, 600, 9000)).orElseThrow();

        assertThat(signal.metric()).isEqualTo(ChurnSignal.Metric.POINTS);
        assertThat(signal.recentDaily()).isCloseTo(20.0, within(1e-9));
        assertThat(signal.baselineDaily()).isCloseTo(100.0, within(1e-9));
        assertThat(signal.dropRate()).isCloseTo(0.8, within(1e-9));
    }

    @Test
    void whenBothTriggerTheLargerDropIsReported() {
        // 活跃：基线 90 (1/天)，近期 6 (0.2/天) → 下降 0.8；积分：基线 9000 (100/天)，近期 300 (10/天) → 下降 0.9
        ChurnSignal signal = detect(metrics(6, 90, 300, 9000)).orElseThrow();

        assertThat(signal.metric()).isEqualTo(ChurnSignal.Metric.POINTS);
        assertThat(signal.dropRate()).isCloseTo(0.9, within(1e-9));
    }

    @Test
    void whenBothDropByTheSameAmountActivityWins() {
        // 两个指标都下降 1.0（近期全是 0）
        ChurnSignal signal = detect(metrics(0, 9, 0, 10)).orElseThrow();

        assertThat(signal.metric()).isEqualTo(ChurnSignal.Metric.ACTIVITY);
        assertThat(signal.dropRate()).isCloseTo(1.0, within(1e-9));
    }
}
```

`backend/src/test/java/com/jiaxian/analytics/kol/SegmentClassifierTest.java`：

```java
package com.jiaxian.analytics.kol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentClassifierTest {

    private static Segment classify(int r, int f, int m, boolean alert, Segment previous, boolean rising) {
        return SegmentClassifier.classify(new RfmScores(r, f, m), alert, previous, rising);
    }

    // ---- 1. 流失预警 ----

    @Test
    void alertAfterStarOrSteadyOrChurnRiskIsChurnRisk() {
        assertThat(classify(1, 1, 1, true, Segment.STAR_KOL, false)).isEqualTo(Segment.CHURN_RISK);
        assertThat(classify(1, 1, 1, true, Segment.STEADY, false)).isEqualTo(Segment.CHURN_RISK);
        assertThat(classify(1, 1, 1, true, Segment.CHURN_RISK, false)).isEqualTo(Segment.CHURN_RISK);
    }

    @Test
    void alertAfterAnyOtherPreviousSegmentIsNotChurnRisk() {
        // 上一期是观察中 → 即使有预警也不算"流失预警"，落到后面的规则（这里 (4,4,3) 不满足新人/沉寂 → 观察中）
        assertThat(classify(4, 4, 3, true, Segment.OBSERVE, false)).isEqualTo(Segment.OBSERVE);
        assertThat(classify(1, 1, 1, true, Segment.DORMANT, false)).isEqualTo(Segment.DORMANT);
    }

    @Test
    void withoutAPreviousPeriodAlertFallsBackToTheAverageScore() {
        assertThat(classify(3, 3, 3, true, null, false)).isEqualTo(Segment.CHURN_RISK);   // avg 3.0
        assertThat(classify(3, 3, 2, true, null, false)).isEqualTo(Segment.OBSERVE);      // avg 2.67
    }

    @Test
    void alertTakesPriorityOverStar() {
        assertThat(classify(5, 5, 5, true, Segment.STAR_KOL, false)).isEqualTo(Segment.CHURN_RISK);
    }

    // ---- 2. 明星 KOL / 4. 稳定贡献者 ----

    @Test
    void starNeedsAverageAtLeastFourAndNoAlert() {
        assertThat(classify(4, 4, 4, false, null, false)).isEqualTo(Segment.STAR_KOL);   // 4.0
        assertThat(classify(5, 5, 5, false, Segment.STAR_KOL, false)).isEqualTo(Segment.STAR_KOL);
    }

    @Test
    void steadyIsAverageBetweenThreeAndFour() {
        assertThat(classify(5, 3, 3, false, null, false)).isEqualTo(Segment.STEADY);   // 3.67
        assertThat(classify(3, 3, 3, false, null, false)).isEqualTo(Segment.STEADY);   // 3.0
        assertThat(classify(4, 4, 3, false, null, false)).isEqualTo(Segment.STEADY);   // 3.67
    }

    @Test
    void alertWithoutQualifyingHistoryNeverMakesAStarOrSteady() {
        assertThat(classify(5, 3, 3, true, Segment.OBSERVE, false)).isEqualTo(Segment.OBSERVE);
    }

    // ---- 3. 成长潜力新人 ----

    @Test
    void risingNeedsHighRecencyLowFrequencyAndMonetaryAndAnUpwardTrend() {
        assertThat(classify(5, 2, 1, false, null, true)).isEqualTo(Segment.RISING);
        assertThat(classify(4, 1, 1, false, null, true)).isEqualTo(Segment.RISING);
        assertThat(classify(5, 2, 1, false, null, false)).isEqualTo(Segment.OBSERVE);   // 趋势没有上升
        assertThat(classify(3, 2, 1, false, null, true)).isEqualTo(Segment.OBSERVE);    // R 不够高
        assertThat(classify(5, 3, 1, false, null, true)).isEqualTo(Segment.STEADY);     // F 不够低 → 不是新人；平均分 3.0 → 稳定贡献者
    }

    @Test
    void risingBeatsSteadyWhenTheAverageIsExactlyThree() {
        // (5,2,2) 平均分恰好 3.0：典型的"近期活跃、产出低、趋势上升"，应判为新人而不是稳定贡献者
        assertThat(classify(5, 2, 2, false, null, true)).isEqualTo(Segment.RISING);
        assertThat(classify(5, 2, 2, false, null, false)).isEqualTo(Segment.STEADY);
    }

    // ---- 5. 沉寂用户 / 6. 观察中 ----

    @Test
    void dormantNeedsAllThreeScoresAtMostTwo() {
        assertThat(classify(1, 1, 1, false, null, false)).isEqualTo(Segment.DORMANT);
        assertThat(classify(2, 2, 2, false, null, false)).isEqualTo(Segment.DORMANT);
        assertThat(classify(2, 2, 3, false, null, false)).isEqualTo(Segment.OBSERVE);
    }

    @Test
    void everythingElseIsObserve() {
        assertThat(classify(3, 3, 2, false, null, false)).isEqualTo(Segment.OBSERVE);   // avg 2.67
        assertThat(classify(1, 4, 3, false, null, false)).isEqualTo(Segment.OBSERVE);   // avg 2.67
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest='ChurnDetectorTest,SegmentClassifierTest'`
Expected: 编译失败。

- [ ] **Step 3: 写实现**

`backend/src/main/java/com/jiaxian/analytics/kol/ChurnDetector.java`：

```java
package com.jiaxian.analytics.kol;

import java.util.Optional;

/**
 * 纯逻辑：流失预警。近期和基线折算成日均，活跃次数和积分两个指标分别判断；
 * 基线日均为 0 的指标不参与；任一指标下降比例严格大于阈值即触发，两个都触发时记下降更多的，并列记活跃次数。
 */
public final class ChurnDetector {

    private ChurnDetector() {
    }

    public static Optional<ChurnSignal> detect(BloggerMetrics metrics, int recentDays, int baselineDays,
                                               double dropThreshold) {
        Optional<ChurnSignal> activity = signal(ChurnSignal.Metric.ACTIVITY,
            metrics.recentActivity(), metrics.baselineActivity(), recentDays, baselineDays, dropThreshold);
        Optional<ChurnSignal> points = signal(ChurnSignal.Metric.POINTS,
            metrics.recentPoints(), metrics.baselinePoints(), recentDays, baselineDays, dropThreshold);

        if (activity.isPresent() && points.isPresent()) {
            return points.get().dropRate() > activity.get().dropRate() ? points : activity;
        }
        return activity.isPresent() ? activity : points;
    }

    private static Optional<ChurnSignal> signal(ChurnSignal.Metric metric, long recentTotal, long baselineTotal,
                                                int recentDays, int baselineDays, double dropThreshold) {
        double baselineDaily = baselineTotal / (double) baselineDays;
        if (baselineDaily <= 0) {
            return Optional.empty();
        }
        double recentDaily = recentTotal / (double) recentDays;
        double dropRate = 1 - recentDaily / baselineDaily;
        if (dropRate > dropThreshold) {
            return Optional.of(new ChurnSignal(metric, recentDaily, baselineDaily, dropRate));
        }
        return Optional.empty();
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/SegmentClassifier.java`：

```java
package com.jiaxian.analytics.kol;

/**
 * 纯逻辑：分层规则，按顺序判断，命中即停：
 * 流失预警 → 明星 KOL → 成长潜力新人 → 稳定贡献者 → 沉寂用户 → 观察中。
 *
 * <p>成长潜力新人排在稳定贡献者之前：R=5、F=2、M=2 的平均分恰好是 3.0，若先判稳定贡献者就会吞掉它。
 */
public final class SegmentClassifier {

    private SegmentClassifier() {
    }

    /**
     * @param previous    上一期的标签，没有上一期时为 null
     * @param risingTrend 近期积分是否高于上一个近期积分
     */
    public static Segment classify(RfmScores scores, boolean churnAlert, Segment previous, boolean risingTrend) {
        double average = scores.average();

        if (churnAlert && wasStarOrSteady(previous, average)) {
            return Segment.CHURN_RISK;
        }
        if (!churnAlert && average >= 4) {
            return Segment.STAR_KOL;
        }
        if (scores.r() >= 4 && scores.f() <= 2 && scores.m() <= 2 && risingTrend) {
            return Segment.RISING;
        }
        if (!churnAlert && average >= 3) {
            return Segment.STEADY;
        }
        if (scores.r() <= 2 && scores.f() <= 2 && scores.m() <= 2) {
            return Segment.DORMANT;
        }
        return Segment.OBSERVE;
    }

    /** "曾属于明星/稳定"：有上一期时看上一期标签（流失预警本身延续），没有时用当期平均分 ≥ 3 代替。 */
    private static boolean wasStarOrSteady(Segment previous, double currentAverage) {
        if (previous == null) {
            return currentAverage >= 3;
        }
        return previous == Segment.STAR_KOL || previous == Segment.STEADY || previous == Segment.CHURN_RISK;
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn test -Dtest='ChurnDetectorTest,SegmentClassifierTest'`
Expected: `ChurnDetectorTest` 8 个、`SegmentClassifierTest` 11 个，全部通过。

- [ ] **Step 5: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src
git commit -m "feat: add churn detector and segment classifier

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 三张表、文案库种子与文案服务

**Files:**
- Modify: `backend/src/main/resources/schema.sql`（末尾追加）、`backend/src/main/resources/data.sql`（末尾追加）
- Create: `backend/src/main/java/com/jiaxian/analytics/kol/{PlaybookAlertStatus,PlaybookEntry,PlaybookRequest,PlaybookLookup,PlaybookRepository,PlaybookService}.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/kol/PlaybookServiceIT.java`

- [ ] **Step 1: 追加表结构**

在 `backend/src/main/resources/schema.sql` 末尾追加：

```sql

CREATE TABLE IF NOT EXISTS blogger_rfm_score (
    as_of_date         DATE NOT NULL,
    employee_id        BIGINT NOT NULL REFERENCES employee(id) ON DELETE CASCADE,
    rank_no            INT NOT NULL,
    rolling_points_12m BIGINT NOT NULL,
    recency_days       INT,
    frequency          INT NOT NULL,
    monetary           BIGINT NOT NULL,
    r_score            INT NOT NULL,
    f_score            INT NOT NULL,
    m_score            INT NOT NULL,
    segment_label      VARCHAR(20) NOT NULL,
    churn_alert        BOOLEAN NOT NULL,
    PRIMARY KEY (as_of_date, employee_id)
);

CREATE TABLE IF NOT EXISTS churn_alert (
    alert_date      DATE NOT NULL,
    employee_id     BIGINT NOT NULL REFERENCES employee(id) ON DELETE CASCADE,
    metric          VARCHAR(10) NOT NULL CHECK (metric IN ('ACTIVITY', 'POINTS')),
    recent_daily    DOUBLE PRECISION NOT NULL,
    baseline_daily  DOUBLE PRECISION NOT NULL,
    drop_rate       DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (alert_date, employee_id)
);

CREATE TABLE IF NOT EXISTS action_playbook (
    id                  BIGSERIAL PRIMARY KEY,
    segment_label       VARCHAR(20) NOT NULL,
    alert_status        VARCHAR(10) NOT NULL CHECK (alert_status IN ('NO_ALERT', 'ALERT')),
    recommendation_text VARCHAR(1000) NOT NULL,
    UNIQUE (segment_label, alert_status)
);
```

在 `backend/src/main/resources/data.sql` 末尾追加（幂等，不覆盖运营改过的文案）：

```sql

INSERT INTO action_playbook (segment_label, alert_status, recommendation_text) VALUES
    ('STAR_KOL', 'NO_ALERT', '纳入“专家专栏”资源位重点推荐，邀请参与线下分享/评审活动，保持曝光激励'),
    ('STEADY', 'NO_ALERT', '保持常规运营：定期推送热门话题与约稿机会，鼓励向明星KOL梯队成长'),
    ('RISING', 'NO_ALERT', '加入新人扶持计划，给予首页曝光加权，缩短内容从发布到获得反馈的周期'),
    ('CHURN_RISK', 'ALERT', '运营同学私信关怀，了解活跃下降原因，推荐开放专属专栏权限或约稿邀请'),
    ('DORMANT', 'NO_ALERT', '纳入低成本触达清单（邮件/群通知召回），不做重点人工投入'),
    ('OBSERVE', 'NO_ALERT', '暂不做专项运营，持续观察下一周期的分层变化')
ON DUPLICATE KEY UPDATE recommendation_text = recommendation_text;
```

- [ ] **Step 2: 写失败的测试**

`backend/src/test/java/com/jiaxian/analytics/kol/PlaybookServiceIT.java`：

```java
package com.jiaxian.analytics.kol;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PlaybookServiceIT {

    @Autowired private PlaybookService service;

    @Test
    void seedDataIsPresent() {
        List<PlaybookEntry> entries = service.list();

        assertThat(entries).extracting(e -> e.segmentLabel() + "/" + e.alertStatus()).contains(
            "STAR_KOL/NO_ALERT", "STEADY/NO_ALERT", "RISING/NO_ALERT",
            "CHURN_RISK/ALERT", "DORMANT/NO_ALERT", "OBSERVE/NO_ALERT");
        assertThat(service.lookup().find(Segment.STAR_KOL, false)).contains("专家专栏");
    }

    @Test
    void createUpdateAndDelete() {
        PlaybookEntry created = service.create(new PlaybookRequest(Segment.OBSERVE, PlaybookAlertStatus.ALERT, "观察中且有预警：先人工看一眼"));

        assertThat(created.id()).isPositive();
        assertThat(created.segmentLabel()).isEqualTo(Segment.OBSERVE);
        assertThat(created.alertStatus()).isEqualTo(PlaybookAlertStatus.ALERT);

        PlaybookEntry updated = service.update(created.id(),
            new PlaybookRequest(Segment.OBSERVE, PlaybookAlertStatus.ALERT, "改过的文案"));
        assertThat(updated.recommendationText()).isEqualTo("改过的文案");
        assertThat(service.list()).extracting(PlaybookEntry::id).contains(created.id());

        service.delete(created.id());
        assertThat(service.list()).extracting(PlaybookEntry::id).doesNotContain(created.id());
    }

    @Test
    void duplicateSegmentAndAlertStatusIsAConflict() {
        assertThatThrownBy(() -> service.create(
            new PlaybookRequest(Segment.STAR_KOL, PlaybookAlertStatus.NO_ALERT, "重复")))
            .isInstanceOf(PlaybookConflictException.class);

        PlaybookEntry other = service.create(
            new PlaybookRequest(Segment.OBSERVE, PlaybookAlertStatus.ALERT, "另一条"));
        assertThatThrownBy(() -> service.update(other.id(),
            new PlaybookRequest(Segment.STAR_KOL, PlaybookAlertStatus.NO_ALERT, "改成已存在的键")))
            .isInstanceOf(PlaybookConflictException.class);
        // 更新成自己原来的键不算冲突
        assertThat(service.update(other.id(),
            new PlaybookRequest(Segment.OBSERVE, PlaybookAlertStatus.ALERT, "只改文案")).recommendationText())
            .isEqualTo("只改文案");
    }

    @Test
    void missingEntriesAreNotFound() {
        assertThatThrownBy(() -> service.update(-1L,
            new PlaybookRequest(Segment.OBSERVE, PlaybookAlertStatus.ALERT, "x")))
            .isInstanceOf(PlaybookNotFoundException.class);
        assertThatThrownBy(() -> service.delete(-1L)).isInstanceOf(PlaybookNotFoundException.class);
    }

    @Test
    void invalidRequestsAreRejected() {
        assertThatThrownBy(() -> service.create(new PlaybookRequest(null, PlaybookAlertStatus.ALERT, "x")))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("segmentLabel");
        assertThatThrownBy(() -> service.create(new PlaybookRequest(Segment.OBSERVE, null, "x")))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("alertStatus");
        assertThatThrownBy(() -> service.create(new PlaybookRequest(Segment.OBSERVE, PlaybookAlertStatus.ALERT, "  ")))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("recommendationText");
        assertThatThrownBy(() -> service.create(
            new PlaybookRequest(Segment.OBSERVE, PlaybookAlertStatus.ALERT, "字".repeat(1001))))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("1000");
    }

    @Test
    void lookupPrefersTheExactMatchThenFallsBackToNoAlertThenNull() {
        PlaybookLookup lookup = service.lookup();

        // CHURN_RISK 只有 ALERT 的种子：精确命中
        assertThat(lookup.find(Segment.CHURN_RISK, true)).contains("私信关怀");
        // DORMANT 有预警但没有 (DORMANT, ALERT)：回退到 (DORMANT, NO_ALERT)
        assertThat(lookup.find(Segment.DORMANT, true)).contains("低成本触达");
        // CHURN_RISK 没有 (CHURN_RISK, NO_ALERT) 也没有回退可用：为 null
        assertThat(lookup.find(Segment.CHURN_RISK, false)).isNull();
    }
}
```

- [ ] **Step 3: 运行，确认失败**

Run: `cd backend && mvn test -Dtest=PlaybookServiceIT`
Expected: 编译失败。

- [ ] **Step 4: 写实现**

`backend/src/main/java/com/jiaxian/analytics/kol/PlaybookAlertStatus.java`：

```java
package com.jiaxian.analytics.kol;

public enum PlaybookAlertStatus {
    NO_ALERT, ALERT
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/PlaybookEntry.java`：

```java
package com.jiaxian.analytics.kol;

public record PlaybookEntry(long id, Segment segmentLabel, PlaybookAlertStatus alertStatus,
                            String recommendationText) {
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/PlaybookRequest.java`：

```java
package com.jiaxian.analytics.kol;

/** 创建/更新建议文案的请求体。 */
public record PlaybookRequest(Segment segmentLabel, PlaybookAlertStatus alertStatus, String recommendationText) {
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/PlaybookLookup.java`：

```java
package com.jiaxian.analytics.kol;

import java.util.List;

/** 一次性载入全部文案后在内存里查找：先精确匹配 (分层, 预警状态)，再回退到 (分层, NO_ALERT)，都没有则返回 null。 */
public final class PlaybookLookup {

    private final List<PlaybookEntry> entries;

    public PlaybookLookup(List<PlaybookEntry> entries) {
        this.entries = entries;
    }

    public String find(Segment segment, boolean churnAlert) {
        PlaybookAlertStatus wanted = churnAlert ? PlaybookAlertStatus.ALERT : PlaybookAlertStatus.NO_ALERT;
        String fallback = null;
        for (PlaybookEntry entry : entries) {
            if (entry.segmentLabel() != segment) {
                continue;
            }
            if (entry.alertStatus() == wanted) {
                return entry.recommendationText();
            }
            if (entry.alertStatus() == PlaybookAlertStatus.NO_ALERT) {
                fallback = entry.recommendationText();
            }
        }
        return fallback;
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/PlaybookRepository.java`：

```java
package com.jiaxian.analytics.kol;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class PlaybookRepository {

    private static final String SELECT =
        "SELECT id, segment_label, alert_status, recommendation_text FROM action_playbook";

    private static final RowMapper<PlaybookEntry> MAPPER = (rs, rowNum) -> new PlaybookEntry(
        rs.getLong(1), Segment.valueOf(rs.getString(2)),
        PlaybookAlertStatus.valueOf(rs.getString(3)), rs.getString(4));

    private final JdbcTemplate jdbc;

    public PlaybookRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PlaybookEntry> list() {
        return jdbc.query(SELECT + " ORDER BY id", MAPPER);
    }

    public Optional<PlaybookEntry> find(long id) {
        return jdbc.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<PlaybookEntry> findByKey(Segment segment, PlaybookAlertStatus alertStatus) {
        return jdbc.query(SELECT + " WHERE segment_label = ? AND alert_status = ?", MAPPER,
            segment.name(), alertStatus.name()).stream().findFirst();
    }

    public void insert(Segment segment, PlaybookAlertStatus alertStatus, String text) {
        jdbc.update("INSERT INTO action_playbook (segment_label, alert_status, recommendation_text) VALUES (?, ?, ?)",
            segment.name(), alertStatus.name(), text);
    }

    public int update(long id, Segment segment, PlaybookAlertStatus alertStatus, String text) {
        return jdbc.update(
            "UPDATE action_playbook SET segment_label = ?, alert_status = ?, recommendation_text = ? WHERE id = ?",
            segment.name(), alertStatus.name(), text, id);
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM action_playbook WHERE id = ?", id);
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/PlaybookService.java`：

```java
package com.jiaxian.analytics.kol;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 建议文案库：校验、冲突检测、增删改查；以及给名单查询用的一次性查找器。 */
@Service
public class PlaybookService {

    static final int MAX_TEXT_LENGTH = 1000;

    private final PlaybookRepository repository;

    public PlaybookService(PlaybookRepository repository) {
        this.repository = repository;
    }

    public List<PlaybookEntry> list() {
        return repository.list();
    }

    public PlaybookLookup lookup() {
        return new PlaybookLookup(repository.list());
    }

    @Transactional
    public PlaybookEntry create(PlaybookRequest request) {
        validate(request);
        if (repository.findByKey(request.segmentLabel(), request.alertStatus()).isPresent()) {
            throw conflict(request);
        }
        repository.insert(request.segmentLabel(), request.alertStatus(), request.recommendationText().trim());
        return repository.findByKey(request.segmentLabel(), request.alertStatus()).orElseThrow();
    }

    @Transactional
    public PlaybookEntry update(long id, PlaybookRequest request) {
        validate(request);
        repository.find(id).orElseThrow(() -> new PlaybookNotFoundException(id));
        repository.findByKey(request.segmentLabel(), request.alertStatus())
            .filter(other -> other.id() != id)
            .ifPresent(other -> {
                throw conflict(request);
            });
        repository.update(id, request.segmentLabel(), request.alertStatus(), request.recommendationText().trim());
        return repository.find(id).orElseThrow();
    }

    @Transactional
    public void delete(long id) {
        if (repository.delete(id) == 0) {
            throw new PlaybookNotFoundException(id);
        }
    }

    private static PlaybookConflictException conflict(PlaybookRequest request) {
        return new PlaybookConflictException("A playbook entry for " + request.segmentLabel()
            + " / " + request.alertStatus() + " already exists");
    }

    private static void validate(PlaybookRequest request) {
        if (request.segmentLabel() == null) {
            throw new InvalidKolQueryException("segmentLabel is required");
        }
        if (request.alertStatus() == null) {
            throw new InvalidKolQueryException("alertStatus is required");
        }
        String text = request.recommendationText();
        if (text == null || text.isBlank()) {
            throw new InvalidKolQueryException("recommendationText must not be blank");
        }
        if (text.trim().length() > MAX_TEXT_LENGTH) {
            throw new InvalidKolQueryException("recommendationText must be at most " + MAX_TEXT_LENGTH + " characters");
        }
    }
}
```

- [ ] **Step 5: 运行，确认通过**

Run: `cd backend && mvn test -Dtest=PlaybookServiceIT`
Expected: `Tests run: 6, Failures: 0, Errors: 0`

如果启动时 `data.sql` 报错，先用 gsql 直接试那条 `INSERT … ON DUPLICATE KEY UPDATE recommendation_text = recommendation_text`；中文弯引号 `“专家专栏”` 如果被编码问题破坏，改用普通中文书名号 `「专家专栏」` 并同步测试里的断言（断言只匹配"专家专栏"三个字）。

- [ ] **Step 6: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src
git commit -m "feat: add KOL tables, playbook seed data and playbook service

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 名单与指标取数

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/kol/KolMetricsRepository.java`
- Create: `backend/src/test/java/com/jiaxian/analytics/kol/KolTestFixtures.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/kol/KolMetricsRepositoryIT.java`

统计日取 2019-06-30，窗口手算：`end` = 2019-07-01（开区间）；近 90 天起点 2019-04-02；近期起点 2019-06-01；基线起点 2019-03-03（`recentStart − 90 天`）；上一个近期起点 2019-05-02；12 个月起点 2018-07-01。

- [ ] **Step 1: 写夹具**

`backend/src/test/java/com/jiaxian/analytics/kol/KolTestFixtures.java`：

```java
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
```

> 注意：`ActiveActionLog` 的 `@Builder` 字段按 Plan 1 现有测试 `FactAndPointsRepositoryIT` 的写法（`employeeId`、`actionType`（枚举 `ActiveActionType`）、`refId`、`occurTime`、`simulationBatchId`），`refId` 不设即为 null。

- [ ] **Step 2: 写失败的测试**

`backend/src/test/java/com/jiaxian/analytics/kol/KolMetricsRepositoryIT.java`：

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
```

- [ ] **Step 3: 运行，确认失败**

Run: `cd backend && mvn test -Dtest=KolMetricsRepositoryIT`
Expected: 编译失败（`KolMetricsRepository` 不存在）。

- [ ] **Step 4: 写实现**

`backend/src/main/java/com/jiaxian/analytics/kol/KolMetricsRepository.java`：

```java
package com.jiaxian.analytics.kol;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 取统计日 asOf 的名单（近 12 个月积分前 N）和每人的各窗口指标。所有窗口按天对齐，右端是 asOf 次日零点（开区间）：
 * <ul>
 *   <li>近 90 天：[asOf − (window-days − 1), asOf]</li>
 *   <li>近期：[asOf − (recent-days − 1), asOf]，起点记为 recentStart</li>
 *   <li>基线：[recentStart − baseline-days, recentStart)</li>
 *   <li>上一个近期：[recentStart − recent-days, recentStart)</li>
 * </ul>
 */
@Repository
public class KolMetricsRepository {

    private record ActivityRow(Timestamp last, long window, long recent, long baseline) {
    }

    private record PointsRow(long window, long recent, long baseline, long previousRecent) {
    }

    private final JdbcTemplate jdbc;

    public KolMetricsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<BloggerMetrics> loadPopulation(LocalDate asOf, KolProperties props) {
        if (props.getTopN() < 1) {
            // LIMIT 以字面量写进 SQL，必须确认是安全的正整数。
            throw new IllegalArgumentException("top-n must be positive");
        }
        LocalDate recentStartDate = asOf.minusDays(props.getRecentDays() - 1L);
        Timestamp end = ts(asOf.plusDays(1));
        Timestamp rollingStart = ts(asOf.plusDays(1).minusYears(1));
        Timestamp windowStart = ts(asOf.minusDays(props.getWindowDays() - 1L));
        Timestamp recentStart = ts(recentStartDate);
        Timestamp baselineStart = ts(recentStartDate.minusDays(props.getBaselineDays()));
        Timestamp previousRecentStart = ts(recentStartDate.minusDays(props.getRecentDays()));

        // 1. 名单：近 12 个月积分前 N，积分相同按员工 id 升序
        Map<Long, Long> rolling = new LinkedHashMap<>();
        jdbc.query("SELECT employee_id, SUM(points) FROM points_ledger"
                + " WHERE occur_time >= ? AND occur_time < ? GROUP BY employee_id"
                + " ORDER BY SUM(points) DESC, employee_id ASC LIMIT " + props.getTopN(),
            rs -> {
                rolling.put(rs.getLong(1), rs.getLong(2));
            }, rollingStart, end);
        if (rolling.isEmpty()) {
            return List.of();
        }

        // 2. 活跃行为（发帖/评论/登录）：最近一次时间 + 三个窗口内的次数
        Map<Long, ActivityRow> activity = new HashMap<>();
        jdbc.query("SELECT employee_id, MAX(occur_time),"
                + " SUM(CASE WHEN occur_time >= ? THEN 1 ELSE 0 END),"
                + " SUM(CASE WHEN occur_time >= ? THEN 1 ELSE 0 END),"
                + " SUM(CASE WHEN occur_time >= ? AND occur_time < ? THEN 1 ELSE 0 END)"
                + " FROM active_action_log WHERE action_type IN ('POST', 'COMMENT', 'LOGIN') AND occur_time < ?"
                + " GROUP BY employee_id",
            rs -> {
                activity.put(rs.getLong(1),
                    new ActivityRow(rs.getTimestamp(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)));
            }, windowStart, recentStart, baselineStart, recentStart, end);

        // 3. 积分：近 90 天、近期、基线、上一个近期
        Timestamp earliest = min(windowStart, min(baselineStart, previousRecentStart));
        Map<Long, PointsRow> points = new HashMap<>();
        jdbc.query("SELECT employee_id,"
                + " SUM(CASE WHEN occur_time >= ? THEN points ELSE 0 END),"
                + " SUM(CASE WHEN occur_time >= ? THEN points ELSE 0 END),"
                + " SUM(CASE WHEN occur_time >= ? AND occur_time < ? THEN points ELSE 0 END),"
                + " SUM(CASE WHEN occur_time >= ? AND occur_time < ? THEN points ELSE 0 END)"
                + " FROM points_ledger WHERE occur_time >= ? AND occur_time < ? GROUP BY employee_id",
            rs -> {
                points.put(rs.getLong(1),
                    new PointsRow(rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)));
            }, windowStart, recentStart, baselineStart, recentStart, previousRecentStart, recentStart, earliest, end);

        List<BloggerMetrics> population = new ArrayList<>();
        int rank = 0;
        for (Map.Entry<Long, Long> entry : rolling.entrySet()) {
            long employeeId = entry.getKey();
            ActivityRow a = activity.get(employeeId);
            PointsRow p = points.get(employeeId);
            Integer recencyDays = a == null || a.last() == null ? null
                : (int) ChronoUnit.DAYS.between(a.last().toLocalDateTime().toLocalDate(), asOf);
            population.add(new BloggerMetrics(employeeId, ++rank, entry.getValue(), recencyDays,
                a == null ? 0 : (int) a.window(), p == null ? 0 : p.window(),
                a == null ? 0 : a.recent(), a == null ? 0 : a.baseline(),
                p == null ? 0 : p.recent(), p == null ? 0 : p.baseline(), p == null ? 0 : p.previousRecent()));
        }
        return population;
    }

    private static Timestamp ts(LocalDate date) {
        return Timestamp.valueOf(date.atStartOfDay());
    }

    private static Timestamp min(Timestamp a, Timestamp b) {
        return a.before(b) ? a : b;
    }
}
```

- [ ] **Step 5: 运行，确认通过**

Run: `cd backend && mvn test -Dtest=KolMetricsRepositoryIT`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 6: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src
git commit -m "feat: add KOL population and window metrics query

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 每周结果落库、重算服务与定时任务

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/kol/{KolRfmRepository,KolRebuildService,KolScheduler}.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/kol/KolRebuildServiceIT.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/kol/KolSchedulerTest.java`

三人场景（统计日 2019-06-30）的手算：
- 名单（12 个月积分）：Q 600（名次1）、R 300（名次2）、P 10（名次3）。
- 指标：P 的 F=9（04-02..04-10 全在近 90 天内）、M=0（03-10 的积分在近 90 天之前）、R=81 天；Q 的 F=30、M=600、R=0；R 的 F=15、M=300、R=0。
- 五分位（n=3，`1 + (更差人数×5)/3`）：F：P 1、R 2、Q 4；M：P 1、R 2、Q 4；R（新近度）：Q、R 并列都是 0 天（更差的只有 P）→ 2，P → 1。所以 Q = (2,4,4) 平均 3.33，R = (2,2,2)，P = (1,1,1)。
- 预警：P 基线（03-03..05-31）活跃 9 次 → 0.1/天，近期 0 → 下降 1.0（积分指标也是 1.0，并列取活跃次数）；Q、R 基线为 0 不参与。
- 分层：Q 平均 3.33、无预警 → `STEADY`；R `(2,2,2)` → `DORMANT`；P 有预警但没有上一期、平均分 1 < 3 → 不是流失预警，落到 `(1,1,1)` → `DORMANT`；如果上一期是 `STAR_KOL`，则为 `CHURN_RISK`。

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/jiaxian/analytics/kol/KolRebuildServiceIT.java`：

```java
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
```

`backend/src/test/java/com/jiaxian/analytics/kol/KolSchedulerTest.java`：

```java
package com.jiaxian.analytics.kol;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KolSchedulerTest {

    @Test
    void rebuildsTodayEveryWeek() {
        KolRebuildService service = mock(KolRebuildService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-05T01:00:00Z"), ZoneOffset.UTC);

        new KolScheduler(service, clock).rebuildWeekly();

        verify(service).rebuild(LocalDate.of(2026, 10, 5));
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest='KolRebuildServiceIT,KolSchedulerTest'`
Expected: 编译失败。

- [ ] **Step 3: 写实现**

`backend/src/main/java/com/jiaxian/analytics/kol/KolRfmRepository.java`：

```java
package com.jiaxian.analytics.kol;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 每周 RFM 结果和流失预警的读写。日期列在 A 兼容模式下是 timestamp，一律绑定 Timestamp。 */
@Repository
public class KolRfmRepository {

    public record ScoreRow(BloggerMetrics metrics, RfmScores scores, Segment segment, boolean churnAlert) {
    }

    public record AlertRow(long employeeId, ChurnSignal signal) {
    }

    private static final int[] SCORE_TYPES = {Types.TIMESTAMP, Types.BIGINT, Types.INTEGER, Types.BIGINT,
        Types.INTEGER, Types.INTEGER, Types.BIGINT, Types.INTEGER, Types.INTEGER, Types.INTEGER,
        Types.VARCHAR, Types.BOOLEAN};

    private static final int[] ALERT_TYPES = {Types.TIMESTAMP, Types.BIGINT, Types.VARCHAR,
        Types.DOUBLE, Types.DOUBLE, Types.DOUBLE};

    private final JdbcTemplate jdbc;

    public KolRfmRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 最近一次早于 asOf 的重算里每个人的标签；没有更早的重算则为空。 */
    public Map<Long, Segment> previousSegments(LocalDate asOf) {
        Map<Long, Segment> previous = new HashMap<>();
        jdbc.query("SELECT employee_id, segment_label FROM blogger_rfm_score WHERE as_of_date ="
                + " (SELECT MAX(as_of_date) FROM blogger_rfm_score WHERE as_of_date < ?)",
            rs -> {
                previous.put(rs.getLong(1), Segment.valueOf(rs.getString(2)));
            }, ts(asOf));
        return previous;
    }

    public void deleteFor(LocalDate asOf) {
        jdbc.update("DELETE FROM churn_alert WHERE alert_date = ?", ts(asOf));
        jdbc.update("DELETE FROM blogger_rfm_score WHERE as_of_date = ?", ts(asOf));
    }

    public void insertScores(LocalDate asOf, List<ScoreRow> rows) {
        List<Object[]> batch = new ArrayList<>();
        for (ScoreRow row : rows) {
            BloggerMetrics m = row.metrics();
            batch.add(new Object[] {ts(asOf), m.employeeId(), m.rank(), m.rollingPoints12m(), m.recencyDays(),
                m.frequency(), m.monetary(), row.scores().r(), row.scores().f(), row.scores().m(),
                row.segment().name(), row.churnAlert()});
        }
        jdbc.batchUpdate("INSERT INTO blogger_rfm_score (as_of_date, employee_id, rank_no, rolling_points_12m,"
            + " recency_days, frequency, monetary, r_score, f_score, m_score, segment_label, churn_alert)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", batch, SCORE_TYPES);
    }

    public void insertAlerts(LocalDate asOf, List<AlertRow> rows) {
        List<Object[]> batch = new ArrayList<>();
        for (AlertRow row : rows) {
            ChurnSignal signal = row.signal();
            batch.add(new Object[] {ts(asOf), row.employeeId(), signal.metric().name(),
                signal.recentDaily(), signal.baselineDaily(), signal.dropRate()});
        }
        jdbc.batchUpdate("INSERT INTO churn_alert (alert_date, employee_id, metric,"
            + " recent_daily, baseline_daily, drop_rate) VALUES (?, ?, ?, ?, ?, ?)", batch, ALERT_TYPES);
    }

    public Optional<LocalDate> latestAsOf() {
        Timestamp latest = jdbc.queryForObject("SELECT MAX(as_of_date) FROM blogger_rfm_score", Timestamp.class);
        return Optional.ofNullable(latest).map(t -> t.toLocalDateTime().toLocalDate());
    }

    private static Timestamp ts(LocalDate date) {
        return Timestamp.valueOf(date.atStartOfDay());
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/KolRebuildService.java`：

```java
package com.jiaxian.analytics.kol;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 重算统计日 asOf 的名单、RFM 打分、流失预警和分层。先删后插，同一统计日重复重算结果完全一样。 */
@Service
public class KolRebuildService {

    static final int MAX_WEEKS = 52;

    private final KolMetricsRepository metricsRepository;
    private final KolRfmRepository rfmRepository;
    private final KolProperties properties;
    private final Clock clock;

    public KolRebuildService(KolMetricsRepository metricsRepository, KolRfmRepository rfmRepository,
                             KolProperties properties, Clock clock) {
        this.metricsRepository = metricsRepository;
        this.rfmRepository = rfmRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public KolRebuildResult rebuild(LocalDate asOf) {
        if (asOf.isAfter(LocalDate.now(clock))) {
            throw new InvalidKolQueryException("asOf must not be in the future");
        }
        List<BloggerMetrics> population = metricsRepository.loadPopulation(asOf, properties);
        Map<Long, RfmScores> scores = RfmScorer.score(population);
        Map<Long, Segment> previous = rfmRepository.previousSegments(asOf);

        List<KolRfmRepository.ScoreRow> scoreRows = new ArrayList<>();
        List<KolRfmRepository.AlertRow> alertRows = new ArrayList<>();
        for (BloggerMetrics metrics : population) {
            Optional<ChurnSignal> signal = ChurnDetector.detect(metrics, properties.getRecentDays(),
                properties.getBaselineDays(), properties.getChurnDropThreshold());
            boolean alert = signal.isPresent();
            boolean risingTrend = metrics.recentPoints() > metrics.previousRecentPoints();
            RfmScores rfm = scores.get(metrics.employeeId());
            Segment segment = SegmentClassifier.classify(rfm, alert, previous.get(metrics.employeeId()), risingTrend);

            scoreRows.add(new KolRfmRepository.ScoreRow(metrics, rfm, segment, alert));
            signal.ifPresent(s -> alertRows.add(new KolRfmRepository.AlertRow(metrics.employeeId(), s)));
        }

        rfmRepository.deleteFor(asOf);
        rfmRepository.insertScores(asOf, scoreRows);
        rfmRepository.insertAlerts(asOf, alertRows);
        return new KolRebuildResult(asOf, population.size(), alertRows.size());
    }

    /**
     * 从旧到新依次重算 asOf − 7×(weeks−1) … asOf，让"上一期标签"链条成立，用来补历史。
     * 放在同一个事务里（rebuild 是自调用，事务靠这里的注解提供）。
     */
    @Transactional
    public List<KolRebuildResult> rebuildWeeks(LocalDate asOf, int weeks) {
        if (weeks < 1 || weeks > MAX_WEEKS) {
            throw new InvalidKolQueryException("weeks must be between 1 and " + MAX_WEEKS);
        }
        List<KolRebuildResult> results = new ArrayList<>();
        for (int i = weeks - 1; i >= 0; i--) {
            results.add(rebuild(asOf.minusDays(7L * i)));
        }
        return results;
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/KolScheduler.java`：

```java
package com.jiaxian.analytics.kol;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/** 每周一凌晨以"今天"为统计日重算 KOL 名单与分层。 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "analytics.kol.schedule.enabled", havingValue = "true", matchIfMissing = true)
public class KolScheduler {

    private final KolRebuildService rebuildService;
    private final Clock clock;

    public KolScheduler(KolRebuildService rebuildService, Clock clock) {
        this.rebuildService = rebuildService;
        this.clock = clock;
    }

    @Scheduled(cron = "${analytics.kol.rebuild-cron:0 0 1 * * MON}")
    public void rebuildWeekly() {
        rebuildService.rebuild(LocalDate.now(clock));
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn test -Dtest='KolRebuildServiceIT,KolSchedulerTest'`
Expected: `KolRebuildServiceIT` 9 个、`KolSchedulerTest` 1 个，全部通过。

- [ ] **Step 5: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src
git commit -m "feat: add KOL weekly rebuild service, persistence and scheduler

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 名单查询、文案库与重算的 HTTP 接口

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/kol/{BloggerRow,BloggerItem,BloggerList,BloggerListRepository,BloggerListService}.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/kol/{BloggerController,KolAdminController,PlaybookController,KolExceptionHandler}.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/kol/BloggerListServiceTest.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/kol/BloggerListServiceIT.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/kol/KolControllerIT.java`

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/jiaxian/analytics/kol/BloggerListServiceTest.java`（Mockito：没有重算记录、默认取最近一次）：

```java
package com.jiaxian.analytics.kol;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BloggerListServiceTest {

    private final BloggerListRepository listRepository = mock(BloggerListRepository.class);
    private final KolRfmRepository rfmRepository = mock(KolRfmRepository.class);
    private final PlaybookService playbookService = mock(PlaybookService.class);
    private final BloggerListService service = new BloggerListService(listRepository, rfmRepository, playbookService);

    @Test
    void withoutAnyRebuildTheListIsEmptyAndAsOfIsNull() {
        when(rfmRepository.latestAsOf()).thenReturn(Optional.empty());

        BloggerList list = service.list(null, null, null, 50, 0);

        assertThat(list.asOf()).isNull();
        assertThat(list.total()).isZero();
        assertThat(list.items()).isEmpty();
        assertThat(list.segmentCounts()).hasSize(Segment.values().length).allSatisfy((s, c) -> assertThat(c).isZero());
        verify(listRepository, never()).find(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void defaultsToTheLatestRebuild() {
        LocalDate latest = LocalDate.of(2026, 3, 1);
        when(rfmRepository.latestAsOf()).thenReturn(Optional.of(latest));
        when(playbookService.lookup()).thenReturn(new PlaybookLookup(List.of()));
        when(listRepository.count(latest, null, null)).thenReturn(0L);
        when(listRepository.find(eq(latest), eq(null), eq(null), eq(50), eq(0))).thenReturn(List.of());
        when(listRepository.segmentCounts(latest)).thenReturn(Map.of());

        BloggerList list = service.list(null, null, null, 50, 0);

        assertThat(list.asOf()).isEqualTo(latest);
        assertThat(list.segmentCounts()).hasSize(Segment.values().length);
    }
}
```

`backend/src/test/java/com/jiaxian/analytics/kol/BloggerListServiceIT.java`（用 Task 5 的三人场景，`asOf` 显式取 2019-06-30）：

```java
package com.jiaxian.analytics.kol;

import com.jiaxian.analytics.repository.ActiveActionLogRepository;
import com.jiaxian.analytics.repository.EmployeeRepository;
import com.jiaxian.analytics.repository.PointsLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class BloggerListServiceIT {

    private static final LocalDate AS_OF = LocalDate.of(2019, 6, 30);

    @Autowired private BloggerListService service;
    @Autowired private KolRebuildService rebuildService;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ActiveActionLogRepository activeActionLogRepository;
    @Autowired private PointsLedgerRepository pointsLedgerRepository;

    private KolTestFixtures.ChurnScenario s;

    @BeforeEach
    void setUp() {
        s = new KolTestFixtures(employeeRepository, activeActionLogRepository, pointsLedgerRepository)
            .churnScenario();
        rebuildService.rebuild(AS_OF);
    }

    @Test
    void listsEveryoneByRankWithScoresSegmentAndRecommendation() {
        BloggerList list = service.list(AS_OF, null, null, 50, 0);

        assertThat(list.asOf()).isEqualTo(AS_OF);
        assertThat(list.total()).isEqualTo(3);
        assertThat(list.items()).extracting(BloggerItem::employeeId)
            .containsExactly(s.q().getId(), s.r().getId(), s.p().getId());

        BloggerItem q = list.items().get(0);
        assertThat(q.name()).isEqualTo("KOL测试员工Q");
        assertThat(q.rank()).isEqualTo(1);
        assertThat(q.rollingPoints12m()).isEqualTo(600);
        assertThat(q.recencyDays()).isEqualTo(0);
        assertThat(q.frequency()).isEqualTo(30);
        assertThat(q.monetary()).isEqualTo(600);
        assertThat(q.rScore()).isEqualTo(2);
        assertThat(q.fScore()).isEqualTo(4);
        assertThat(q.mScore()).isEqualTo(4);
        assertThat(q.segment()).isEqualTo(Segment.STEADY);
        assertThat(q.segmentName()).isEqualTo("稳定贡献者");
        assertThat(q.churnAlert()).isFalse();
        assertThat(q.recommendation()).contains("常规运营");

        BloggerItem p = list.items().get(2);
        assertThat(p.recencyDays()).isEqualTo(81);
        assertThat(p.churnAlert()).isTrue();
        // (DORMANT, ALERT) 没有文案 → 回退到 (DORMANT, NO_ALERT)
        assertThat(p.recommendation()).contains("低成本触达");
    }

    @Test
    void segmentCountsAreUnfilteredAndTotalIsFiltered() {
        BloggerList dormant = service.list(AS_OF, Segment.DORMANT, null, 50, 0);

        assertThat(dormant.total()).isEqualTo(2);
        assertThat(dormant.items()).extracting(BloggerItem::employeeId).containsExactly(s.r().getId(), s.p().getId());
        assertThat(dormant.segmentCounts().get(Segment.DORMANT)).isEqualTo(2);
        assertThat(dormant.segmentCounts().get(Segment.STEADY)).isEqualTo(1);
        assertThat(dormant.segmentCounts().get(Segment.STAR_KOL)).isZero();
    }

    @Test
    void filtersByAlert() {
        assertThat(service.list(AS_OF, null, true, 50, 0).items())
            .extracting(BloggerItem::employeeId).containsExactly(s.p().getId());
        assertThat(service.list(AS_OF, null, false, 50, 0).items())
            .extracting(BloggerItem::employeeId).containsExactly(s.q().getId(), s.r().getId());
        assertThat(service.list(AS_OF, Segment.STEADY, true, 50, 0).items()).isEmpty();
    }

    @Test
    void paginates() {
        BloggerList page = service.list(AS_OF, null, null, 1, 1);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).extracting(BloggerItem::employeeId).containsExactly(s.r().getId());
    }

    @Test
    void anAsOfWithoutARebuildIsEmpty() {
        BloggerList list = service.list(LocalDate.of(2019, 1, 1), null, null, 50, 0);

        assertThat(list.asOf()).isEqualTo(LocalDate.of(2019, 1, 1));
        assertThat(list.total()).isZero();
        assertThat(list.items()).isEmpty();
    }

    @Test
    void rejectsBadLimitAndOffset() {
        assertThatThrownBy(() -> service.list(AS_OF, null, null, 0, 0))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> service.list(AS_OF, null, null, 1001, 0))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> service.list(AS_OF, null, null, 50, -1))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("offset");
    }
}
```

`backend/src/test/java/com/jiaxian/analytics/kol/KolControllerIT.java`（HTTP；不依赖具体数据，2019 年没有真实数据，文案库的创建测试用完即删）：

```java
package com.jiaxian.analytics.kol;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KolControllerIT {

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

    private HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    // ---- 名单 ----

    @Test
    void listForAnAsOfWithoutARebuildIsEmptyButStructured() {
        ResponseEntity<JsonNode> response = rest.getForEntity(
            url("/api/bloggers/top1000?asOf=2019-06-30&limit=10"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body.get("asOf").asText()).isEqualTo("2019-06-30");
        assertThat(body.get("total").asLong()).isZero();
        assertThat(body.get("items")).isEmpty();
        assertThat(body.get("segmentCounts").get("STAR_KOL").asLong()).isZero();
        assertThat(body.get("segmentCounts").size()).isEqualTo(Segment.values().length);
    }

    @Test
    void listRejectsBadParameters() {
        assertGetBadRequest("/api/bloggers/top1000?segment=VIP", "segment");
        assertGetBadRequest("/api/bloggers/top1000?alert=maybe", "alert");
        assertGetBadRequest("/api/bloggers/top1000?asOf=2019-6-30", "asOf");
        assertGetBadRequest("/api/bloggers/top1000?limit=0", "limit");
        assertGetBadRequest("/api/bloggers/top1000?limit=1001", "limit");
        assertGetBadRequest("/api/bloggers/top1000?offset=-1", "offset");
    }

    // ---- 重算 ----

    @Test
    void rebuildEndpointReturnsOneResultPerWeek() {
        ResponseEntity<JsonNode> response = rest.postForEntity(
            url("/api/admin/analytics/kol/rebuild?asOf=2019-06-30&weeks=2"), null, JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody().get(0).get("asOf").asText()).isEqualTo("2019-06-23");
        assertThat(response.getBody().get(1).get("asOf").asText()).isEqualTo("2019-06-30");
        assertThat(response.getBody().get(1).get("population").asInt()).isZero();   // 2019 年没有任何积分
        assertThat(response.getBody().get(1).get("alerts").asInt()).isZero();
    }

    @Test
    void rebuildEndpointRejectsBadRequests() {
        for (String path : new String[] {
            "/api/admin/analytics/kol/rebuild?asOf=2999-01-01", "/api/admin/analytics/kol/rebuild?weeks=0",
            "/api/admin/analytics/kol/rebuild?weeks=53", "/api/admin/analytics/kol/rebuild?asOf=2019-6-30"}) {
            ResponseEntity<String> response = rest.postForEntity(url(path), null, String.class);
            assertThat(response.getStatusCode().value()).as(path).isEqualTo(400);
        }
    }

    // ---- 文案库 ----

    @Test
    void playbookCrudRoundTrip() {
        ResponseEntity<JsonNode> list = rest.getForEntity(url("/api/admin/playbook"), JsonNode.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody().isArray()).isTrue();
        assertThat(list.getBody().size()).isGreaterThanOrEqualTo(6);

        // OBSERVE/ALERT 不在种子里，创建后用完即删
        ResponseEntity<JsonNode> created = rest.postForEntity(url("/api/admin/playbook"),
            json("{\"segmentLabel\":\"OBSERVE\",\"alertStatus\":\"ALERT\",\"recommendationText\":\"先人工看一眼\"}"),
            JsonNode.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        long id = created.getBody().get("id").asLong();
        try {
            ResponseEntity<String> duplicate = rest.postForEntity(url("/api/admin/playbook"),
                json("{\"segmentLabel\":\"OBSERVE\",\"alertStatus\":\"ALERT\",\"recommendationText\":\"重复\"}"),
                String.class);
            assertThat(duplicate.getStatusCode().value()).isEqualTo(409);

            ResponseEntity<JsonNode> updated = rest.exchange(url("/api/admin/playbook/" + id), HttpMethod.PUT,
                json("{\"segmentLabel\":\"OBSERVE\",\"alertStatus\":\"ALERT\",\"recommendationText\":\"改过的文案\"}"),
                JsonNode.class);
            assertThat(updated.getStatusCode().value()).isEqualTo(200);
            assertThat(updated.getBody().get("recommendationText").asText()).isEqualTo("改过的文案");
        } finally {
            ResponseEntity<Void> deleted = rest.exchange(url("/api/admin/playbook/" + id), HttpMethod.DELETE, null, Void.class);
            assertThat(deleted.getStatusCode().value()).isEqualTo(204);
        }
        ResponseEntity<String> deletedAgain = rest.exchange(
            url("/api/admin/playbook/" + id), HttpMethod.DELETE, null, String.class);
        assertThat(deletedAgain.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void playbookRejectsInvalidBodies() {
        ResponseEntity<String> badEnum = rest.postForEntity(url("/api/admin/playbook"),
            json("{\"segmentLabel\":\"VIP\",\"alertStatus\":\"ALERT\",\"recommendationText\":\"x\"}"), String.class);
        assertThat(badEnum.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<String> blank = rest.postForEntity(url("/api/admin/playbook"),
            json("{\"segmentLabel\":\"OBSERVE\",\"alertStatus\":\"ALERT\",\"recommendationText\":\"   \"}"), String.class);
        assertThat(blank.getStatusCode().value()).isEqualTo(400);
        assertThat(blank.getBody()).contains("recommendationText");

        ResponseEntity<String> missing = rest.exchange(url("/api/admin/playbook/-1"), HttpMethod.PUT,
            json("{\"segmentLabel\":\"OBSERVE\",\"alertStatus\":\"ALERT\",\"recommendationText\":\"x\"}"), String.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest='BloggerListServiceTest,BloggerListServiceIT,KolControllerIT'`
Expected: 编译失败。

- [ ] **Step 3: 写实现**

`backend/src/main/java/com/jiaxian/analytics/kol/BloggerRow.java`：

```java
package com.jiaxian.analytics.kol;

/** blogger_rfm_score 联员工表的一行。 */
public record BloggerRow(long employeeId, String name, int rank, long rollingPoints12m, Integer recencyDays,
                         int frequency, long monetary, int rScore, int fScore, int mScore,
                         Segment segment, boolean churnAlert) {
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/BloggerItem.java`：

```java
package com.jiaxian.analytics.kol;

/** 名单接口返回的一位博主：原始指标、三个分数、分层标签、预警标记和匹配的建议文案。 */
public record BloggerItem(long employeeId, String name, int rank, long rollingPoints12m, Integer recencyDays,
                          int frequency, long monetary, int rScore, int fScore, int mScore,
                          Segment segment, String segmentName, boolean churnAlert, String recommendation) {
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/BloggerList.java`：

```java
package com.jiaxian.analytics.kol;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * @param asOf          统计日；没有任何重算记录时为 null
 * @param total         筛选后的总人数
 * @param segmentCounts 该统计日未筛选的各分层人数（每个分层都有键，没有人则为 0）
 */
public record BloggerList(LocalDate asOf, long total, Map<Segment, Long> segmentCounts, List<BloggerItem> items) {
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/BloggerListRepository.java`：

```java
package com.jiaxian.analytics.kol;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 名单查询：按统计日、分层、预警筛选，按名单名次排序并分页。LIMIT/OFFSET 以校验过的整数字面量写进 SQL。 */
@Repository
public class BloggerListRepository {

    private final JdbcTemplate jdbc;

    public BloggerListRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<BloggerRow> find(LocalDate asOf, Segment segment, Boolean alert, int limit, int offset) {
        if (limit < 1 || offset < 0) {
            throw new IllegalArgumentException("limit must be positive and offset must not be negative");
        }
        Where where = where(asOf, segment, alert);
        String sql = "SELECT r.employee_id, e.name, r.rank_no, r.rolling_points_12m, r.recency_days, r.frequency,"
            + " r.monetary, r.r_score, r.f_score, r.m_score, r.segment_label, r.churn_alert"
            + " FROM blogger_rfm_score r JOIN employee e ON e.id = r.employee_id" + where.sql()
            + " ORDER BY r.rank_no, r.employee_id LIMIT " + limit + " OFFSET " + offset;
        return jdbc.query(sql, (rs, rowNum) -> {
            int recency = rs.getInt(5);
            Integer recencyDays = rs.wasNull() ? null : recency;
            return new BloggerRow(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4), recencyDays,
                rs.getInt(6), rs.getLong(7), rs.getInt(8), rs.getInt(9), rs.getInt(10),
                Segment.valueOf(rs.getString(11)), rs.getBoolean(12));
        }, where.args().toArray());
    }

    public long count(LocalDate asOf, Segment segment, Boolean alert) {
        Where where = where(asOf, segment, alert);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM blogger_rfm_score r" + where.sql(),
            Long.class, where.args().toArray());
        return count == null ? 0 : count;
    }

    /** 该统计日未筛选的各分层人数；没有人的分层不出现（由 Service 补零）。 */
    public Map<Segment, Long> segmentCounts(LocalDate asOf) {
        Map<Segment, Long> counts = new EnumMap<>(Segment.class);
        jdbc.query("SELECT segment_label, COUNT(*) FROM blogger_rfm_score WHERE as_of_date = ? GROUP BY segment_label",
            rs -> {
                counts.put(Segment.valueOf(rs.getString(1)), rs.getLong(2));
            }, ts(asOf));
        return counts;
    }

    private static Where where(LocalDate asOf, Segment segment, Boolean alert) {
        Where where = new Where();
        where.and("r.as_of_date = ?", ts(asOf));
        if (segment != null) {
            where.and("r.segment_label = ?", segment.name());
        }
        if (alert != null) {
            where.and("r.churn_alert = ?", alert);
        }
        return where;
    }

    private static Timestamp ts(LocalDate date) {
        return Timestamp.valueOf(date.atStartOfDay());
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

`backend/src/main/java/com/jiaxian/analytics/kol/BloggerListService.java`：

```java
package com.jiaxian.analytics.kol;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 名单查询：校验参数、默认取最近一次重算、补齐各分层人数、给每人匹配建议文案。 */
@Service
public class BloggerListService {

    static final int MAX_LIMIT = 1000;

    private final BloggerListRepository listRepository;
    private final KolRfmRepository rfmRepository;
    private final PlaybookService playbookService;

    public BloggerListService(BloggerListRepository listRepository, KolRfmRepository rfmRepository,
                              PlaybookService playbookService) {
        this.listRepository = listRepository;
        this.rfmRepository = rfmRepository;
        this.playbookService = playbookService;
    }

    /** asOf 为 null 表示最近一次重算；segment / alert 为 null 表示不筛选。 */
    public BloggerList list(LocalDate asOf, Segment segment, Boolean alert, int limit, int offset) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidKolQueryException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new InvalidKolQueryException("offset must not be negative");
        }
        LocalDate effective = asOf != null ? asOf : rfmRepository.latestAsOf().orElse(null);
        if (effective == null) {
            return new BloggerList(null, 0, zeroCounts(Map.of()), List.of());
        }

        long total = listRepository.count(effective, segment, alert);
        Map<Segment, Long> counts = zeroCounts(listRepository.segmentCounts(effective));
        PlaybookLookup lookup = playbookService.lookup();
        List<BloggerItem> items = listRepository.find(effective, segment, alert, limit, offset).stream()
            .map(row -> new BloggerItem(row.employeeId(), row.name(), row.rank(), row.rollingPoints12m(),
                row.recencyDays(), row.frequency(), row.monetary(), row.rScore(), row.fScore(), row.mScore(),
                row.segment(), row.segment().displayName(), row.churnAlert(),
                lookup.find(row.segment(), row.churnAlert())))
            .toList();
        return new BloggerList(effective, total, counts, items);
    }

    private static Map<Segment, Long> zeroCounts(Map<Segment, Long> counts) {
        Map<Segment, Long> filled = new EnumMap<>(Segment.class);
        for (Segment segment : Segment.values()) {
            filled.put(segment, counts.getOrDefault(segment, 0L));
        }
        return filled;
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/KolExceptionHandler.java`：

```java
package com.jiaxian.analytics.kol;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 只作用于 KOL 接口：参数问题 400，文案不存在 404，文案键冲突 409，响应体是一句说明原因的文字。 */
@RestControllerAdvice(assignableTypes = {BloggerController.class, KolAdminController.class, PlaybookController.class})
public class KolExceptionHandler {

    @ExceptionHandler(InvalidKolQueryException.class)
    public ResponseEntity<String> invalidQuery(InvalidKolQueryException e) {
        return text(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<String> typeMismatch(MethodArgumentTypeMismatchException e) {
        return text(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + e.getName() + "'");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<String> missingParameter(MissingServletRequestParameterException e) {
        return text(HttpStatus.BAD_REQUEST, "Missing required parameter '" + e.getParameterName() + "'");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> unreadableBody(HttpMessageNotReadableException e) {
        return text(HttpStatus.BAD_REQUEST, "Request body is not valid JSON or contains an invalid value");
    }

    @ExceptionHandler(PlaybookNotFoundException.class)
    public ResponseEntity<String> notFound(PlaybookNotFoundException e) {
        return text(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(PlaybookConflictException.class)
    public ResponseEntity<String> conflict(PlaybookConflictException e) {
        return text(HttpStatus.CONFLICT, e.getMessage());
    }

    private static ResponseEntity<String> text(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(message);
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/BloggerController.java`：

```java
package com.jiaxian.analytics.kol;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/bloggers")
public class BloggerController {

    private final BloggerListService service;

    public BloggerController(BloggerListService service) {
        this.service = service;
    }

    /**
     * TOP N 博主分层名单。asOf 省略取最近一次重算；segment 为大写枚举；alert 为 true/false；
     * 响应里的 segmentCounts 是该统计日未筛选的各分层人数，total 是筛选后的总数。
     */
    @GetMapping("/top1000")
    public BloggerList top(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
        @RequestParam(required = false) Segment segment,
        @RequestParam(required = false) Boolean alert,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0") int offset) {
        return service.list(asOf, segment, alert, limit, offset);
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/KolAdminController.java`：

```java
package com.jiaxian.analytics.kol;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin/analytics/kol")
public class KolAdminController {

    private final KolRebuildService rebuildService;
    private final Clock clock;

    public KolAdminController(KolRebuildService rebuildService, Clock clock) {
        this.rebuildService = rebuildService;
        this.clock = clock;
    }

    /** asOf 默认今天，weeks 默认 1（最多 52）；weeks > 1 时从旧到新补最近几周，用于补历史。 */
    @PostMapping("/rebuild")
    public List<KolRebuildResult> rebuild(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
        @RequestParam(defaultValue = "1") int weeks) {
        return rebuildService.rebuildWeeks(asOf != null ? asOf : LocalDate.now(clock), weeks);
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/kol/PlaybookController.java`：

```java
package com.jiaxian.analytics.kol;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 建议文案库维护：(分层, 预警状态) 唯一，重复返回 409，不存在返回 404。 */
@RestController
@RequestMapping("/api/admin/playbook")
public class PlaybookController {

    private final PlaybookService service;

    public PlaybookController(PlaybookService service) {
        this.service = service;
    }

    @GetMapping
    public List<PlaybookEntry> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<PlaybookEntry> create(@RequestBody PlaybookRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    public PlaybookEntry update(@PathVariable long id, @RequestBody PlaybookRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn test -Dtest='BloggerListServiceTest,BloggerListServiceIT,KolControllerIT'`
Expected: `BloggerListServiceTest` 2 个、`BloggerListServiceIT` 6 个、`KolControllerIT` 6 个，全部通过。

排查：
- `churn_alert = ?` 绑定布尔参数报类型错误：改成 `churn_alert = true/false` 的字面量拼接（Boolean 只有两个值，安全）。
- `rs.getBoolean` 读 `BOOLEAN` 列异常：确认列类型确实是 `BOOLEAN`，不是 A 兼容模式映射成的别的类型。

- [ ] **Step 5: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src
git commit -m "feat: add KOL blogger list, playbook CRUD and rebuild endpoints

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 模拟器博主画像

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/simulator/{BloggerPersona,PersonaAssigner,PersonaLoginPlanner}.java`
- Modify: `backend/src/main/java/com/jiaxian/analytics/simulator/SimulationConfig.java`
- Modify: `backend/src/main/java/com/jiaxian/analytics/simulator/HistoricalBackfillService.java`
- Modify: `backend/src/main/java/com/jiaxian/analytics/simulator/SimulatorController.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/simulator/PersonaAssignerTest.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/simulator/PersonaLoginPlannerTest.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/simulator/HistoricalBackfillPersonaIT.java`

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/jiaxian/analytics/simulator/PersonaAssignerTest.java`：

```java
package com.jiaxian.analytics.simulator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PersonaAssignerTest {

    private static long count(List<BloggerPersona> personas, BloggerPersona wanted) {
        return personas.stream().filter(p -> p == wanted).count();
    }

    @Test
    void fiftyEmployeesSplitIntoTenTenTenTwentyAndTheRest() {
        List<BloggerPersona> personas = PersonaAssigner.assign(50, 21);

        assertThat(personas).hasSize(50);
        assertThat(count(personas, BloggerPersona.STAR)).isEqualTo(5);
        assertThat(count(personas, BloggerPersona.RISING)).isEqualTo(5);
        assertThat(count(personas, BloggerPersona.CHURNING)).isEqualTo(5);
        assertThat(count(personas, BloggerPersona.DORMANT)).isEqualTo(10);
        assertThat(count(personas, BloggerPersona.RANDOM)).isEqualTo(25);
    }

    @Test
    void tenEmployeesGetOneOneOneTwoAndFiveRandom() {
        List<BloggerPersona> personas = PersonaAssigner.assign(10, 3);

        assertThat(count(personas, BloggerPersona.STAR)).isEqualTo(1);
        assertThat(count(personas, BloggerPersona.RISING)).isEqualTo(1);
        assertThat(count(personas, BloggerPersona.CHURNING)).isEqualTo(1);
        assertThat(count(personas, BloggerPersona.DORMANT)).isEqualTo(2);
        assertThat(count(personas, BloggerPersona.RANDOM)).isEqualTo(5);
    }

    @Test
    void sameSeedAndSizeGiveTheSameAssignmentAndADifferentSeedShufflesIt() {
        assertThat(PersonaAssigner.assign(50, 21)).isEqualTo(PersonaAssigner.assign(50, 21));
        assertThat(PersonaAssigner.assign(50, 21)).isNotEqualTo(PersonaAssigner.assign(50, 22));
    }

    @Test
    void zeroEmployeesGiveAnEmptyList() {
        assertThat(PersonaAssigner.assign(0, 1)).isEmpty();
    }
}
```

`backend/src/test/java/com/jiaxian/analytics/simulator/PersonaLoginPlannerTest.java`：

```java
package com.jiaxian.analytics.simulator;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersonaLoginPlannerTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 3, 1);   // 共 424 天

    private static List<LocalDateTime> plan(BloggerPersona persona) {
        return PersonaLoginPlanner.plan(persona, START, END, new Random(7));
    }

    @Test
    void starLogsInAboutEightyPercentOfDaysAcrossTheWholeHistory() {
        List<LocalDateTime> logins = plan(BloggerPersona.STAR);

        assertThat(logins.size()).isBetween(297, 381);   // 424 天 × 0.8 ± 约 5σ
        assertThat(logins).allSatisfy(t -> {
            assertThat(t.toLocalDate()).isAfterOrEqualTo(START).isBefore(END);
        });
        // 最后 60 天仍然活跃（和流失者的区别）
        assertThat(logins).anyMatch(t -> !t.toLocalDate().isBefore(END.minusDays(60)));
    }

    @Test
    void churningStopsLoggingInSixtyDaysBeforeTheEnd() {
        List<LocalDateTime> logins = plan(BloggerPersona.CHURNING);

        assertThat(logins.size()).isBetween(250, 330);   // 364 天 × 0.8
        assertThat(logins).allSatisfy(t -> assertThat(t.toLocalDate()).isBefore(END.minusDays(60)));
    }

    @Test
    void risingLogsInExactlyTwiceTwentyAndFourDaysBeforeTheEnd() {
        List<LocalDateTime> logins = plan(BloggerPersona.RISING);

        assertThat(logins).containsExactlyInAnyOrder(
            END.minusDays(20).atTime(10, 0), END.minusDays(4).atTime(10, 0));
    }

    @Test
    void risingSkipsLoginsThatWouldFallBeforeTheHistoryStart() {
        List<LocalDateTime> logins = PersonaLoginPlanner.plan(
            BloggerPersona.RISING, END.minusDays(10), END, new Random(7));

        assertThat(logins).containsExactly(END.minusDays(4).atTime(10, 0));
    }

    @Test
    void dormantLogsInEightTimesAllBeforeOneHundredTwentyDaysFromTheEnd() {
        List<LocalDateTime> logins = plan(BloggerPersona.DORMANT);

        assertThat(logins).hasSize(8);
        assertThat(logins).allSatisfy(t -> {
            assertThat(t.toLocalDate()).isAfterOrEqualTo(START).isBefore(END.minusDays(120));
        });
    }

    @Test
    void randomPersonaIsNotPlannedHere() {
        assertThatThrownBy(() -> plan(BloggerPersona.RANDOM)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSameSeedGivesTheSamePlan() {
        assertThat(plan(BloggerPersona.STAR)).isEqualTo(plan(BloggerPersona.STAR));
    }
}
```

`backend/src/test/java/com/jiaxian/analytics/simulator/HistoricalBackfillPersonaIT.java`（真实回填；会清空模拟表，和其他回填测试一样）：

```java
package com.jiaxian.analytics.simulator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class HistoricalBackfillPersonaIT {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 3, 1);
    private static final int EMPLOYEES = 20;
    private static final long SEED = 5;

    @Autowired private HistoricalBackfillService backfillService;
    @Autowired private JdbcTemplate jdbc;

    private SimulationConfig config(boolean personas) {
        return SimulationConfig.builder()
            .batchId("persona-it").employeeCount(EMPLOYEES).accountCount(8).teamAccountRatio(0.2).contentCount(20)
            .historyStart(START).historyEnd(END).randomSeed(SEED).personas(personas).build();
    }

    private long loginCount(long employeeId, LocalDate from, LocalDate toExclusive) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM active_action_log WHERE employee_id = ?"
                + " AND action_type = 'LOGIN' AND occur_time >= ? AND occur_time < ?", Long.class,
            employeeId, Timestamp.valueOf(from.atStartOfDay()), Timestamp.valueOf(toExclusive.atStartOfDay()));
    }

    @Test
    void personasShapeEachEmployeesLoginPattern() {
        backfillService.runBackfill(config(true));

        List<Long> employeeIds = jdbc.queryForList("SELECT id FROM employee ORDER BY id", Long.class);
        List<BloggerPersona> personas = PersonaAssigner.assign(EMPLOYEES, SEED);
        assertThat(employeeIds).hasSize(EMPLOYEES);

        for (int i = 0; i < EMPLOYEES; i++) {
            long id = employeeIds.get(i);
            switch (personas.get(i)) {
                case STAR -> assertThat(loginCount(id, END.minusDays(60), END)).as("STAR #%d", i).isGreaterThan(30);
                case CHURNING -> {
                    assertThat(loginCount(id, END.minusDays(60), END.plusDays(1))).as("CHURNING #%d", i).isZero();
                    assertThat(loginCount(id, START, END.minusDays(60))).as("CHURNING #%d", i).isGreaterThan(100);
                }
                case RISING -> assertThat(loginCount(id, START, END.plusDays(1))).as("RISING #%d", i).isEqualTo(2);
                case DORMANT -> {
                    assertThat(loginCount(id, START, END.plusDays(1))).as("DORMANT #%d", i).isEqualTo(8);
                    assertThat(loginCount(id, END.minusDays(120), END.plusDays(1))).as("DORMANT #%d", i).isZero();
                }
                case RANDOM -> assertThat(loginCount(id, START, END.plusDays(1))).as("RANDOM #%d", i).isBetween(5L, 24L);
            }
        }
    }

    @Test
    void withoutPersonasEveryoneKeepsTheOriginalRandomLogins() {
        backfillService.runBackfill(config(false));

        List<Long> employeeIds = jdbc.queryForList("SELECT id FROM employee ORDER BY id", Long.class);
        for (long id : employeeIds) {
            assertThat(loginCount(id, START, END.plusDays(1))).isBetween(5L, 24L);
        }
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest='PersonaAssignerTest,PersonaLoginPlannerTest,HistoricalBackfillPersonaIT'`
Expected: 编译失败（`BloggerPersona` 等不存在、`SimulationConfig.personas` 不存在）。

- [ ] **Step 3: 写画像相关实现**

`backend/src/main/java/com/jiaxian/analytics/simulator/BloggerPersona.java`：

```java
package com.jiaxian.analytics.simulator;

/** 模拟员工的博主画像，只影响登录行为：STAR 持续活跃，RISING 近期才冒头，CHURNING 近期突然沉默，DORMANT 长期沉寂，RANDOM 保持原有随机行为。 */
public enum BloggerPersona {
    STAR, RISING, CHURNING, DORMANT, RANDOM
}
```

`backend/src/main/java/com/jiaxian/analytics/simulator/PersonaAssigner.java`：

```java
package com.jiaxian.analytics.simulator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 给员工序号分配画像：10% STAR、10% RISING、10% CHURNING、20% DORMANT，其余 RANDOM（各比例 round 取整）。
 * 用独立的随机流（种子异或一个常量）打乱，不扰动回填的主随机序列；相同的员工数和种子得到相同的结果，
 * 所以画像不用落库，测试用同样的函数就能算出每个员工的画像。
 */
public final class PersonaAssigner {

    private static final long SHUFFLE_SALT = 0x5DEECE66DL;

    private PersonaAssigner() {
    }

    /** 返回长度为 employeeCount 的列表，第 i 个元素是按创建顺序的第 i 个员工的画像。 */
    public static List<BloggerPersona> assign(int employeeCount, long seed) {
        List<BloggerPersona> personas = new ArrayList<>();
        add(personas, BloggerPersona.STAR, Math.round(employeeCount * 0.10));
        add(personas, BloggerPersona.RISING, Math.round(employeeCount * 0.10));
        add(personas, BloggerPersona.CHURNING, Math.round(employeeCount * 0.10));
        add(personas, BloggerPersona.DORMANT, Math.round(employeeCount * 0.20));
        add(personas, BloggerPersona.RANDOM, employeeCount - personas.size());
        Collections.shuffle(personas, new Random(seed ^ SHUFFLE_SALT));
        return personas;
    }

    private static void add(List<BloggerPersona> personas, BloggerPersona persona, long count) {
        for (long i = 0; i < count; i++) {
            personas.add(persona);
        }
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/simulator/PersonaLoginPlanner.java`：

```java
package com.jiaxian.analytics.simulator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 按画像规划一个员工整个历史里的登录时间（end = historyEnd，所有登录都落在 [historyStart, end) 内）：
 * STAR 每天约 80% 的概率登录一次；CHURNING 与 STAR 相同但 end−60 天之后不再登录；
 * RISING 只在 end−20、end−4 各登录一次；DORMANT 在 [historyStart, end−120) 内随机登录 8 次。
 * 这些常量是经验值，用来让各画像落入预期的 RFM 分层，必要时可调整。
 */
public final class PersonaLoginPlanner {

    static final double DAILY_LOGIN_PROBABILITY = 0.8;
    static final int CHURN_SILENT_DAYS = 60;
    static final int DORMANT_SILENT_DAYS = 120;
    static final int DORMANT_LOGIN_COUNT = 8;
    static final int[] RISING_DAYS_BEFORE_END = {20, 4};

    private PersonaLoginPlanner() {
    }

    public static List<LocalDateTime> plan(BloggerPersona persona, LocalDate historyStart, LocalDate historyEnd,
                                           Random random) {
        List<LocalDateTime> logins = new ArrayList<>();
        switch (persona) {
            case STAR -> addDaily(logins, historyStart, historyEnd, random);
            case CHURNING -> addDaily(logins, historyStart, historyEnd.minusDays(CHURN_SILENT_DAYS), random);
            case RISING -> {
                for (int daysBeforeEnd : RISING_DAYS_BEFORE_END) {
                    LocalDate day = historyEnd.minusDays(daysBeforeEnd);
                    if (!day.isBefore(historyStart)) {
                        logins.add(day.atTime(10, 0));
                    }
                }
            }
            case DORMANT -> {
                LocalDate cutoff = historyEnd.minusDays(DORMANT_SILENT_DAYS);
                long span = historyStart.until(cutoff, java.time.temporal.ChronoUnit.DAYS);
                for (int i = 0; span > 0 && i < DORMANT_LOGIN_COUNT; i++) {
                    LocalDate day = historyStart.plusDays(random.nextInt((int) span));
                    logins.add(day.atTime(9 + random.nextInt(8), random.nextInt(60)));
                }
            }
            case RANDOM -> throw new IllegalArgumentException("RANDOM employees keep the original login logic");
        }
        return logins;
    }

    private static void addDaily(List<LocalDateTime> logins, LocalDate from, LocalDate toExclusive, Random random) {
        for (LocalDate day = from; day.isBefore(toExclusive); day = day.plusDays(1)) {
            if (random.nextDouble() < DAILY_LOGIN_PROBABILITY) {
                logins.add(day.atTime(9 + random.nextInt(8), random.nextInt(60)));
            }
        }
    }
}
```

- [ ] **Step 4: 改配置、回填服务和控制器**

`backend/src/main/java/com/jiaxian/analytics/simulator/SimulationConfig.java`：在字段 `private long randomSeed;` 后面加一行：

```java
    /** 是否按博主画像（STAR/RISING/CHURNING/DORMANT/RANDOM）规划登录行为，默认 false，行为与以前完全一致。 */
    private boolean personas;
```

`backend/src/main/java/com/jiaxian/analytics/simulator/HistoricalBackfillService.java`：把登录循环

```java
        for (var employee : entities.getEmployees()) {
            int loginDays = 5 + random.nextInt(20);
            for (int i = 0; i < loginDays; i++) {
                LocalDateTime loginTime = randomTimeInRange(
                    config.getHistoryStart().atStartOfDay(), config.getHistoryEnd().atStartOfDay(), random);
                eventGenerator.generateLogin(employee.getId(), loginTime, config.getBatchId());
            }
        }
```

替换为：

```java
        // 开启画像时，非 RANDOM 画像的员工用独立随机流规划登录；RANDOM 员工保持原有逻辑（沿用主随机流）。
        var employees = entities.getEmployees();
        List<BloggerPersona> personas = config.isPersonas()
            ? PersonaAssigner.assign(employees.size(), config.getRandomSeed()) : null;
        for (int employeeIndex = 0; employeeIndex < employees.size(); employeeIndex++) {
            var employee = employees.get(employeeIndex);
            BloggerPersona persona = personas == null ? BloggerPersona.RANDOM : personas.get(employeeIndex);
            if (persona != BloggerPersona.RANDOM) {
                Random personaRandom = new Random(config.getRandomSeed() * 1_000_003L + employeeIndex);
                for (LocalDateTime loginTime : PersonaLoginPlanner.plan(
                        persona, config.getHistoryStart(), config.getHistoryEnd(), personaRandom)) {
                    eventGenerator.generateLogin(employee.getId(), loginTime, config.getBatchId());
                }
                continue;
            }
            int loginDays = 5 + random.nextInt(20);
            for (int i = 0; i < loginDays; i++) {
                LocalDateTime loginTime = randomTimeInRange(
                    config.getHistoryStart().atStartOfDay(), config.getHistoryEnd().atStartOfDay(), random);
                eventGenerator.generateLogin(employee.getId(), loginTime, config.getBatchId());
            }
        }
```

确认该文件顶部已有 `import java.util.List;`、`import java.util.Random;`、`import java.time.LocalDateTime;`（现有代码已经用到 `List<ContentAuthor>`、`Random`、`LocalDateTime`）；缺少哪个就补上。

`backend/src/main/java/com/jiaxian/analytics/simulator/SimulatorController.java`：在构建 `SimulationConfig` 的链式调用里 `.randomSeed(request.getRandomSeed())` 后面加 `.personas(request.isPersonas())`；在 `BackfillRequest` 里 `private long randomSeed;` 后面加一行：

```java
        private boolean personas;
```

- [ ] **Step 5: 运行，确认通过**

Run: `cd backend && mvn test -Dtest='PersonaAssignerTest,PersonaLoginPlannerTest,HistoricalBackfillPersonaIT'`
Expected: `PersonaAssignerTest` 4 个、`PersonaLoginPlannerTest` 7 个、`HistoricalBackfillPersonaIT` 2 个，全部通过。

- [ ] **Step 6: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`（现有回填测试不受影响，因为 `personas` 默认 false）。

```bash
git add backend/src
git commit -m "feat: add optional blogger personas to the historical backfill

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 8: 端到端：画像是否落入预期分层

**Files:**
- Test: `backend/src/test/java/com/jiaxian/analytics/kol/KolEndToEndIT.java`

开画像回填（50 名员工、种子 21、历史 2025-01-01 ~ 2026-03-01）→ 在 `historyEnd` 补 14 周重算 → 通过 HTTP 取名单 → 按画像检查分层。

- [ ] **Step 1: 写测试**

`backend/src/test/java/com/jiaxian/analytics/kol/KolEndToEndIT.java`：

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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 OpenGauss + 真实 HTTP：开画像回填 → 补 14 周重算 → 名单接口里各画像落入预期分层。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KolEndToEndIT {

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
            .batchId("kol-e2e").employeeCount(EMPLOYEES).accountCount(20).teamAccountRatio(0.25).contentCount(100)
            .historyStart(LocalDate.of(2025, 1, 1)).historyEnd(END).randomSeed(SEED).personas(true).build());
    }

    private JsonNode get(String path) {
        ResponseEntity<JsonNode> response = rest.getForEntity(url(path), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    @Test
    void personasLandInTheExpectedSegments() {
        backfill();

        ResponseEntity<JsonNode> rebuild = rest.postForEntity(
            url("/api/admin/analytics/kol/rebuild?asOf=" + END + "&weeks=14"), null, JsonNode.class);
        assertThat(rebuild.getStatusCode().value()).isEqualTo(200);
        assertThat(rebuild.getBody()).hasSize(14);
        assertThat(rebuild.getBody().get(0).get("asOf").asText()).isEqualTo(END.minusDays(91).toString());
        assertThat(rebuild.getBody().get(13).get("asOf").asText()).isEqualTo(END.toString());
        assertThat(rebuild.getBody().get(13).get("population").asInt()).isGreaterThanOrEqualTo(40);

        // 画像是 (员工数, 种子) 的纯函数：按员工 id 升序和创建顺序对应
        List<Long> employeeIds = jdbc.queryForList("SELECT id FROM employee ORDER BY id", Long.class);
        List<BloggerPersona> personas = PersonaAssigner.assign(EMPLOYEES, SEED);
        assertThat(employeeIds).hasSize(EMPLOYEES);

        JsonNode list = get("/api/bloggers/top1000?limit=1000");
        assertThat(list.get("asOf").asText()).isEqualTo(END.toString());
        Map<Long, JsonNode> byId = new HashMap<>();
        for (JsonNode item : list.get("items")) {
            byId.put(item.get("employeeId").asLong(), item);
        }

        Map<BloggerPersona, List<JsonNode>> byPersona = new EnumMap<>(BloggerPersona.class);
        for (int i = 0; i < EMPLOYEES; i++) {
            JsonNode item = byId.get(employeeIds.get(i));
            if (item != null) {   // 12 个月内没有任何积分的人不在名单里
                byPersona.computeIfAbsent(personas.get(i), p -> new ArrayList<>()).add(item);
            }
        }
        String distribution = describe(byPersona);

        // 流失者：近期完全沉默 → 全部触发预警，并且都是"流失预警"
        List<JsonNode> churning = byPersona.getOrDefault(BloggerPersona.CHURNING, List.of());
        assertThat(churning).as("churning present. " + distribution).hasSizeGreaterThanOrEqualTo(3);
        assertThat(churning).as("churning alerted. " + distribution)
            .allSatisfy(item -> assertThat(item.get("churnAlert").asBoolean()).isTrue());
        assertThat(churning).as("churning segment. " + distribution)
            .allSatisfy(item -> assertThat(item.get("segment").asText()).isEqualTo("CHURN_RISK"));

        // 持续活跃的明星：不触发预警，都是明星 KOL
        List<JsonNode> stars = byPersona.getOrDefault(BloggerPersona.STAR, List.of());
        assertThat(stars).as("stars present. " + distribution).hasSizeGreaterThanOrEqualTo(3);
        assertThat(stars).as("stars segment. " + distribution)
            .allSatisfy(item -> assertThat(item.get("segment").asText()).isEqualTo("STAR_KOL"));

        // 画像只控制登录，发帖/评论仍是随机的：有的沉寂者/新人最近恰好有随机发帖，所以这两类用统计性断言。
        // 沉寂者：大多数新近度分低（≤2），不会是明星或成长新人，且至少一半被判为沉寂用户
        List<JsonNode> dormant = byPersona.getOrDefault(BloggerPersona.DORMANT, List.of());
        assertThat(dormant).as("dormant present. " + distribution).isNotEmpty();
        assertThat(dormant.stream().filter(item -> item.get("rScore").asInt() <= 2).count())
            .as("most dormant have a low recency score. " + distribution)
            .isGreaterThanOrEqualTo(Math.round(dormant.size() * 0.7));
        assertThat(dormant).as("dormant never star or rising. " + distribution)
            .allSatisfy(item -> assertThat(item.get("segment").asText()).isNotIn("STAR_KOL", "RISING"));
        assertThat(dormant.stream().filter(item -> item.get("segment").asText().equals("DORMANT")).count())
            .as("at least half of dormant are DORMANT. " + distribution)
            .isGreaterThanOrEqualTo(Math.round(dormant.size() * 0.5));

        // 新人：最近才冒头，新近度分都高，不会被判为沉寂用户，且至少有一个是成长潜力新人
        List<JsonNode> rising = byPersona.getOrDefault(BloggerPersona.RISING, List.of());
        assertThat(rising).as("rising present. " + distribution).isNotEmpty();
        assertThat(rising).as("rising recency score. " + distribution)
            .allSatisfy(item -> assertThat(item.get("rScore").asInt()).isGreaterThanOrEqualTo(4));
        assertThat(rising).as("rising never dormant. " + distribution)
            .allSatisfy(item -> assertThat(item.get("segment").asText()).isNotEqualTo("DORMANT"));
        assertThat(rising).as("some rising are RISING. " + distribution)
            .anyMatch(item -> item.get("segment").asText().equals("RISING"));

        // 汇总、筛选、分页、建议文案
        JsonNode counts = list.get("segmentCounts");
        long counted = 0;
        for (JsonNode count : counts) {
            counted += count.asLong();
        }
        assertThat(counted).isEqualTo(list.get("total").asLong());

        JsonNode alerted = get("/api/bloggers/top1000?alert=true&limit=1000");
        assertThat(alerted.get("total").asLong()).isGreaterThanOrEqualTo(churning.size());
        for (JsonNode item : alerted.get("items")) {
            assertThat(item.get("churnAlert").asBoolean()).isTrue();
        }
        JsonNode churnList = get("/api/bloggers/top1000?segment=CHURN_RISK&limit=1000");
        assertThat(churnList.get("total").asLong()).isEqualTo(counts.get("CHURN_RISK").asLong());
        for (JsonNode item : churnList.get("items")) {
            assertThat(item.get("recommendation").asText()).contains("私信关怀");
        }

        JsonNode page = get("/api/bloggers/top1000?limit=5&offset=5");
        assertThat(page.get("items")).hasSize(5);
        assertThat(page.get("items").get(0).get("rank").asInt()).isEqualTo(6);

        // 再次回填会 deleteAll() 全部员工：每周结果和预警必须随员工级联清空
        backfill();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blogger_rfm_score", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM churn_alert", Long.class)).isZero();
    }

    /** 失败时帮助调参：列出每个画像的分层分布。 */
    private static String describe(Map<BloggerPersona, List<JsonNode>> byPersona) {
        StringBuilder text = new StringBuilder("distribution=");
        byPersona.forEach((persona, items) -> {
            text.append(persona).append(":");
            items.forEach(item -> text.append(item.get("segment").asText())
                .append("(r").append(item.get("rScore").asInt())
                .append("f").append(item.get("fScore").asInt())
                .append("m").append(item.get("mScore").asInt())
                .append(item.get("churnAlert").asBoolean() ? ",alert" : "").append(") "));
            text.append("| ");
        });
        return text.toString();
    }
}
```

- [ ] **Step 2: 运行**

Run: `cd backend && mvn test -Dtest=KolEndToEndIT`
Expected: `Tests run: 1, Failures: 0, Errors: 0`

**如果某个画像的断言失败**：失败信息里带 `distribution=…`，列出每个画像的每个人落到了哪个分层、三个分数、有没有预警。按下面的办法只调 `PersonaLoginPlanner` 的常量（不要放宽断言）：
- `STAR` 有人不是 `STAR_KOL`：提高 `DAILY_LOGIN_PROBABILITY`（例如 0.9），或看是不是被预警误判。
- 沉寂者/新人只做统计性断言：画像只控制登录，发帖和评论仍是随机的，个别员工最近恰好有随机发帖/评论，新近度分会偏高或落入别的分层，这是预期内的噪声（首次实测：5 个流失者全部是流失预警、5 个明星全部是明星 KOL；10 个沉寂者里 6 个沉寂用户、8 个新近度分 ≤2；5 个新人里 1 个成长潜力新人、全部新近度分 ≥4）。
- `CHURNING` 有人没触发预警：把 `CHURN_SILENT_DAYS` 调大（例如 75），保证近 30 天为 0 且基线期有足够登录。
- `RISING` 没有任何人落入 `RISING`：调整 `RISING_DAYS_BEFORE_END`（例如 `{12, 3}`）；它需要 R 分 ≥4、F/M 分 ≤2，且近期积分高于上一个近期。
- `DORMANT` 没有任何人是 `DORMANT`：减小 `DORMANT_LOGIN_COUNT`（例如 4）。
调整后重新跑 `PersonaLoginPlannerTest`、`HistoricalBackfillPersonaIT`（它们里面的数字如 8 次、20/4 天需要同步），再跑本测试；把调整的常量和原因写进提交说明。

- [ ] **Step 3: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src
git commit -m "test: add end-to-end KOL segmentation test with simulated personas

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 9: 手工验证与收尾

**Files:**
- Modify: `docs/superpowers/specs/2026-09-23-community-analytics-design.md`
- Modify: `docs/superpowers/specs/2026-10-04-kol-segmentation-design.md`（状态行）

- [ ] **Step 1: 启动应用，开画像回填并补 12 周**

```bash
cd backend && mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8093
```

Expected: 日志出现 `Started AnalyticsApplication`。另开终端：

```bash
curl -s -X POST localhost:8093/api/admin/simulator/backfill -H 'Content-Type: application/json' \
  -d '{"batchId":"manual-kol","employeeCount":50,"accountCount":20,"teamAccountRatio":0.25,"contentCount":200,"historyStart":"2025-01-01","historyEnd":"2026-03-01","randomSeed":7,"personas":true}'
curl -s -X POST 'localhost:8093/api/admin/analytics/kol/rebuild?asOf=2026-03-01&weeks=12'
curl -s 'localhost:8093/api/bloggers/top1000?limit=8' | python3 -m json.tool | head -90
curl -s 'localhost:8093/api/bloggers/top1000?alert=true&limit=100' | python3 -c "import json,sys; r=json.load(sys.stdin); print(r['total'], [(i['rank'], i['name'], i['segmentName']) for i in r['items']])"
curl -s -i 'localhost:8093/api/bloggers/top1000?segment=VIP' | sed -n '1p;$p'
curl -s localhost:8093/api/admin/playbook | python3 -m json.tool | head -30
```

Expected：
- rebuild 返回 12 条 `{asOf, population, alerts}`，最后一条 `asOf=2026-03-01`；
- 名单第一页按 `rank` 排序，每人带 `segment`/`segmentName`/`recommendation`；`segmentCounts` 里有明星 KOL、流失预警、沉寂用户等多个分层不为 0；
- `alert=true` 的人里有流失者画像的员工，分层是"流失预警"；
- `segment=VIP` 返回 `HTTP/1.1 400` 和 "Invalid value for parameter 'segment'"；
- 文案库返回 6 条种子。

人工看一眼：明星 KOL 的 R 分、F 分、M 分是否都偏高；流失预警的人最近的 `recencyDays` 是否明显偏大。

- [ ] **Step 2: 关闭应用**

```bash
pkill -f spring-boot:run
sleep 4
lsof -i :8093 || echo "port 8093 free"
```

Expected: 输出 `port 8093 free`（若仍有 java 进程在监听，再 `pkill -f spring-boot:run` 一次并等几秒）。

- [ ] **Step 3: 同步母设计文档并更新状态**

在 `docs/superpowers/specs/2026-09-23-community-analytics-design.md` 第 6 节"建议文案库"表格**之后**加一行说明：

```markdown
> 实现时的取舍（见 [KOL 分层设计](2026-10-04-kol-segmentation-design.md)）：分层表补了兜底标签"观察中"，"成长潜力新人"排在"稳定贡献者"之前判断；`blogger_rfm_score` 的 `period` 实际叫 `as_of_date` 并每周保留历史；`churn_alert` 没有 `status`，多了 `metric` 字段；阈值和参数放 `application.yml` 的 `analytics.kol.*`，建议文案库有完整增删改查；模拟器新增可选的博主画像（只影响登录）。
```

把 `docs/superpowers/specs/2026-10-04-kol-segmentation-design.md` 的 `- 状态：已通过讨论确认` 改为 `- 状态：已实现（实施计划见 [KOL 分层与流失预警](../plans/2026-10-04-kol-segmentation.md)）`。

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add docs
git commit -m "docs: record KOL segmentation design decisions and mark spec implemented

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

之后按 `superpowers:finishing-a-development-branch` 收尾；推送到 GitHub 只在用户要求时进行。

---

## Self-Review

**1. 设计文档覆盖：**
- 第 3 节口径：名单与各窗口 → Task 4（含窗口边界、`windowDays` 可配、Top N、`recencyDays` 为 null）；五分位打分 → Task 1（手算十人、并列、R 为空、单人、全员相同）；流失预警 → Task 2（阈值边界、基线 0、两指标取大、并列取活跃次数）。
- 第 4 节分层规则（含 RISING 先于 STEADY 的顺序）→ Task 2 `SegmentClassifierTest`，每条规则、优先级、冷启动回退、`(5,2,2)` 边界；上一期标签链条 → Task 5。
- 第 5 节数据模型 + 种子 + 文案查找回退 → Task 3；级联 → Task 5 `resultsAreRemovedWhenTheEmployeeIsDeleted` + Task 8。
- 第 6 节重算与触发（先删后插、`rebuildWeeks` 从旧到新、定时、手动、400）→ Task 5、6。
- 第 7 节接口（名单筛选/分页/汇总/文案、文案库增删改查 + 400/404/409）→ Task 6。
- 第 8 节模拟画像（比例、确定性、各画像登录形状、默认关闭不变）→ Task 7；画像是否落入预期分层 → Task 8。
- 第 9 节配置 → Task 1（`KolProperties` + `application.yml`）。
- 第 10 节组件划分 → 文件结构表，全部有任务。
- 第 11 节测试策略 → 纯逻辑（Task 1、2、7）、真实库 IT（Task 3–6）、画像单测（Task 7）、端到端（Task 8）。

**2. 占位符扫描：** 无 TBD/TODO。Task 8 的"失败时调参"是明确的应对措施（给出具体常量和调整方向），不是占位。

**3. 类型一致性：** `BloggerMetrics`（11 个字段，顺序 `employeeId, rank, rollingPoints12m, recencyDays, frequency, monetary, recentActivity, baselineActivity, recentPoints, baselinePoints, previousRecentPoints`）在 Task 1 定义，Task 1 的 `RfmScorerTest`、Task 2 的 `ChurnDetectorTest`、Task 4 的期望值、Task 5 的 `KolRfmRepository` 都按这个顺序使用；`RfmScores(r, f, m)`、`ChurnSignal(metric, recentDaily, baselineDaily, dropRate)`、`KolRebuildResult(asOf, population, alerts)` 在 Task 1 定义，后续一致；`KolMetricsRepository.loadPopulation(asOf, props)` 在 Task 4 定义，Task 5 使用；`KolRfmRepository` 的 `ScoreRow`/`AlertRow`、`previousSegments`、`deleteFor`、`insertScores`、`insertAlerts`、`latestAsOf` 在 Task 5 定义，Task 6 的 `BloggerListService` 使用 `latestAsOf`；`PlaybookService.lookup()` / `PlaybookLookup.find(segment, alert)` 在 Task 3 定义，Task 6 使用；`BloggerListRepository.find/count/segmentCounts` 在 Task 6 内部一致；`PersonaAssigner.assign(count, seed)`、`PersonaLoginPlanner.plan(persona, start, end, random)`、`SimulationConfig.personas` 在 Task 7 定义，Task 8 使用；`KolTestFixtures.churnScenario()` 返回 `ChurnScenario(p, q, r)`，在 Task 4 定义，Task 5、6 使用。
