package com.jiaxian.analytics.simulator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校准验收（设计文档第 2 节里能在小规模验证的项）：真实回填一次 200 员工 × 半年，用 SQL 逐项检查
 * 浏览由登录产生、PV 量级、互动占比、来源占比、没有历史结束日之后的活动、日活无尖峰、周末效应、关注和作者的长尾。
 */
@SpringBootTest
class CalibrationIT {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);
    private static final LocalDate END = LocalDate.of(2025, 7, 1);
    private static final String TS_END = "TIMESTAMP '2025-07-01 00:00:00'";

    @Autowired private HistoricalBackfillService backfillService;
    @Autowired private JdbcTemplate jdbc;

    private static boolean backfilled;

    @BeforeAll
    static void reset() {
        backfilled = false;
    }

    private void ensureBackfill() {
        if (!backfilled) {
            backfillService.runBackfill(SimulationConfig.builder().batchId("calibration-it").employeeCount(200)
                .accountCount(30).teamAccountRatio(0.25).contentCount(300).historyStart(START).historyEnd(END)
                .randomSeed(91).personas(true).build());
            backfilled = true;
        }
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private double number(String sql) {
        return jdbc.queryForObject(sql, Double.class);
    }

    @Test
    void volumeIsDrivenByLoginsAndNothingHappensAfterTheHistoryEnd() {
        ensureBackfill();

        long pv = count("SELECT COUNT(*) FROM pv_log");
        long logins = count("SELECT COUNT(*) FROM active_action_log WHERE action_type = 'LOGIN'");
        assertThat(pv / (double) logins).as("每次登录平均浏览次数").isBetween(1.0, 3.5);
        assertThat(count("SELECT COUNT(*) FROM active_action_log WHERE action_type = 'VIEW'")).isEqualTo(pv);
        for (String sql : new String[] {
            "SELECT COUNT(*) FROM pv_log WHERE view_time >= " + TS_END,
            "SELECT COUNT(*) FROM active_action_log WHERE occur_time >= " + TS_END,
            "SELECT COUNT(*) FROM engagement_log WHERE occur_time >= " + TS_END,
            "SELECT COUNT(*) FROM comment WHERE create_time >= " + TS_END,
            "SELECT COUNT(*) FROM impression WHERE impression_time >= " + TS_END,
            "SELECT COUNT(*) FROM search_log WHERE search_time >= " + TS_END}) {
            assertThat(count(sql)).as(sql).isZero();
        }
    }

    @Test
    void everyViewHappensAfterTheViewersLoginOnTheSameDayAndImpressionsGoToPeopleWhoWereOnline() {
        ensureBackfill();

        assertThat(count("SELECT COUNT(*) FROM pv_log p WHERE NOT EXISTS (SELECT 1 FROM active_action_log a"
            + " WHERE a.employee_id = p.viewer_employee_id AND a.action_type = 'LOGIN'"
            + " AND date_trunc('day', a.occur_time) = date_trunc('day', p.view_time) AND a.occur_time <= p.view_time)")).isZero();
        assertThat(count("SELECT COUNT(*) FROM impression i WHERE i.clicked = FALSE AND NOT EXISTS (SELECT 1 FROM active_action_log a"
            + " WHERE a.employee_id = i.viewer_employee_id AND a.action_type = 'LOGIN'"
            + " AND date_trunc('day', a.occur_time) = date_trunc('day', i.impression_time))")).isZero();
    }

    @Test
    void contentHasRealisticPvAndMostLoggedInPeopleReadSomething() {
        ensureBackfill();

        double median = number("SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY pv) FROM"
            + " (SELECT c.id, COUNT(p.id) pv FROM content c LEFT JOIN pv_log p ON p.content_id = c.id GROUP BY c.id) t");
        long zero = count("SELECT COUNT(*) FROM (SELECT c.id FROM content c LEFT JOIN pv_log p ON p.content_id = c.id"
            + " GROUP BY c.id HAVING COUNT(p.id) = 0) t");
        assertThat(median).as("每篇内容 PV 中位数").isGreaterThanOrEqualTo(20);
        assertThat(zero / 300.0).as("零 PV 内容占比").isLessThan(0.10);

        long loggedIn = count("SELECT COUNT(DISTINCT employee_id) FROM active_action_log WHERE action_type = 'LOGIN'");
        long viewed = count("SELECT COUNT(DISTINCT viewer_employee_id) FROM pv_log");
        assertThat(viewed / (double) loggedIn).as("登录过的人里浏览过的占比").isGreaterThanOrEqualTo(0.85);
    }

    @Test
    void interactionsAreAFewPercentOfPvAndSourceSharesMatchTheReference() {
        ensureBackfill();

        long pv = count("SELECT COUNT(*) FROM pv_log");
        long interactions = count("SELECT COUNT(*) FROM engagement_log WHERE content_id IS NOT NULL")
            + count("SELECT COUNT(*) FROM comment");
        assertThat(interactions / (double) pv).as("互动 / PV").isBetween(0.015, 0.06);

        assertThat(count("SELECT COUNT(*) FROM pv_log WHERE source_type = 'SHARE'") / (double) pv).isBetween(0.36, 0.44);
        assertThat(count("SELECT COUNT(*) FROM pv_log WHERE source_type = 'SEARCH'") / (double) pv).isBetween(0.07, 0.13);
        assertThat(count("SELECT COUNT(*) FROM pv_log WHERE source_type = 'ENTRY'") / (double) pv).isBetween(0.13, 0.21);
        assertThat(count("SELECT COUNT(*) FROM pv_log WHERE source_type = 'OTHER'") / (double) pv).isBetween(0.28, 0.38);
    }

    @Test
    void dailyActiveUsersHaveNoSpikeOnTheLastDayAndWeekendsAreQuieter() {
        ensureBackfill();

        double lastDay = number("SELECT COUNT(DISTINCT employee_id) FROM active_action_log"
            + " WHERE occur_time >= TIMESTAMP '2025-06-30 00:00:00' AND occur_time < " + TS_END);
        double previousWeek = number("SELECT AVG(dau) FROM (SELECT date_trunc('day', occur_time) d, COUNT(DISTINCT employee_id) dau"
            + " FROM active_action_log WHERE occur_time >= TIMESTAMP '2025-06-23 00:00:00'"
            + " AND occur_time < TIMESTAMP '2025-06-30 00:00:00' AND extract(dow from occur_time) NOT IN (0, 6)"
            + " GROUP BY date_trunc('day', occur_time)) t");
        assertThat(lastDay).as("最后一天（周一）日活 / 前一周工作日均值").isLessThanOrEqualTo(previousWeek * 1.3);

        double weekend = number("SELECT AVG(n) FROM (SELECT date_trunc('day', occur_time) d, COUNT(*) n FROM active_action_log"
            + " WHERE action_type = 'LOGIN' AND extract(dow from occur_time) IN (0, 6) GROUP BY 1) t");
        double weekday = number("SELECT AVG(n) FROM (SELECT date_trunc('day', occur_time) d, COUNT(*) n FROM active_action_log"
            + " WHERE action_type = 'LOGIN' AND extract(dow from occur_time) NOT IN (0, 6) GROUP BY 1) t");
        assertThat(weekend / weekday).as("周末 / 工作日登录").isLessThanOrEqualTo(0.55);
    }

    @Test
    void followsAreLongTailedAndAuthorsReadBeforeTheyWrite() {
        ensureBackfill();

        double perPerson = number("SELECT COUNT(*) * 1.0 / COUNT(DISTINCT follower_employee_id) FROM follow_relation");
        assertThat(perPerson).as("人均关注").isBetween(4.0, 12.0);
        double maxFollowers = number("SELECT MAX(n) FROM (SELECT COUNT(*) n FROM follow_relation GROUP BY account_id) t");
        double medianFollowers = number("SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY n) FROM"
            + " (SELECT COUNT(*) n FROM follow_relation GROUP BY account_id) t");
        assertThat(maxFollowers).as("粉丝最多的账号 / 中位数").isGreaterThanOrEqualTo(medianFollowers * 3);

        // 历史期间才加入的作者，第一次发帖不早于加入后第 7 天（发布时间被压在历史结束前一天的除外）
        assertThat(count("SELECT COUNT(*) FROM active_action_log a JOIN employee e ON e.id = a.employee_id"
            + " WHERE a.action_type = 'POST' AND e.community_join_date >= TIMESTAMP '2025-01-01 00:00:00'"
            + " AND a.occur_time < e.community_join_date + INTERVAL '7 days'"
            + " AND a.occur_time < TIMESTAMP '2025-06-30 00:00:00'")).isZero();
        long contentsPerAccountMax = count("SELECT MAX(n) FROM (SELECT COUNT(*) n FROM content GROUP BY account_id) t");
        long contentsPerAccountMedian = Math.round(number("SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY n) FROM"
            + " (SELECT COUNT(*) n FROM content GROUP BY account_id) t"));
        assertThat(contentsPerAccountMax).as("头部账号内容数 / 中位数").isGreaterThanOrEqualTo(contentsPerAccountMedian * 3);
    }
}
