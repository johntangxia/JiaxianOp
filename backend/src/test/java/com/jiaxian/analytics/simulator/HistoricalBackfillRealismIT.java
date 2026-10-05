package com.jiaxian.analytics.simulator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 回填一次（耗时较长），然后对生成数据的"真实度"做一组断言：加入日期约束、沉默读者、同领域浏览、周末规律、频道归属。 */
@SpringBootTest
class HistoricalBackfillRealismIT {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 1);
    private static final int EMPLOYEES = 60;
    private static boolean backfilled;

    @Autowired private HistoricalBackfillService backfillService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeAll
    static void reset() {
        backfilled = false;
    }

    private synchronized void ensureBackfill() {
        if (!backfilled) {
            backfillService.runBackfill(SimulationConfig.builder()
                .batchId("realism-it").employeeCount(EMPLOYEES).accountCount(24).teamAccountRatio(0.25)
                .contentCount(150).historyStart(START).historyEnd(END).randomSeed(11).personas(true).build());
            backfilled = true;
        }
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void contentLivesOnlyInDomainChannelsAndEmployeesBelongToDomains() {
        ensureBackfill();

        assertThat(count("SELECT COUNT(*) FROM content c JOIN channel ch ON ch.id = c.channel_id"
            + " WHERE ch.category <> '领域频道'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM employee e WHERE NOT EXISTS"
            + " (SELECT 1 FROM channel ch WHERE ch.name = e.dept AND ch.category = '领域频道')")).isZero();
    }

    @Test
    void noBehaviourHappensBeforeAnEmployeeJoinedTheCommunity() {
        ensureBackfill();

        assertThat(count("SELECT COUNT(*) FROM active_action_log a JOIN employee e ON e.id = a.employee_id"
            + " WHERE a.occur_time < e.community_join_date")).as("active actions").isZero();
        assertThat(count("SELECT COUNT(*) FROM pv_log p JOIN employee e ON e.id = p.viewer_employee_id"
            + " WHERE p.view_time < e.community_join_date")).as("views").isZero();
        assertThat(count("SELECT COUNT(*) FROM engagement_log g JOIN employee e ON e.id = g.actor_employee_id"
            + " WHERE g.occur_time < e.community_join_date")).as("engagements").isZero();
        assertThat(count("SELECT COUNT(*) FROM comment c JOIN employee e ON e.id = c.employee_id"
            + " WHERE c.create_time < e.community_join_date")).as("comments").isZero();
        assertThat(count("SELECT COUNT(*) FROM content c JOIN content_author a ON a.content_id = c.id"
            + " JOIN employee e ON e.id = a.employee_id WHERE c.publish_time < e.community_join_date"))
            .as("posts").isZero();
    }

    @Test
    void aboutAThirdOfTheViewersNeverInteract() {
        ensureBackfill();

        long viewers = count("SELECT COUNT(DISTINCT viewer_employee_id) FROM pv_log");
        long viewOnly = count("SELECT COUNT(*) FROM (SELECT DISTINCT viewer_employee_id AS id FROM pv_log) v"
            + " WHERE NOT EXISTS (SELECT 1 FROM engagement_log g WHERE g.actor_employee_id = v.id)"
            + " AND NOT EXISTS (SELECT 1 FROM comment c WHERE c.employee_id = v.id)");
        assertThat(viewers).isGreaterThan(EMPLOYEES / 2);
        assertThat(viewOnly / (double) viewers).isBetween(0.15, 0.50);
    }

    @Test
    void mostViewsComeFromTheContentsOwnDomain() {
        ensureBackfill();

        long views = count("SELECT COUNT(*) FROM pv_log");
        long sameDomain = count("SELECT COUNT(*) FROM pv_log p JOIN content c ON c.id = p.content_id"
            + " JOIN channel ch ON ch.id = c.channel_id JOIN employee e ON e.id = p.viewer_employee_id"
            + " WHERE e.dept = ch.name");
        assertThat(sameDomain / (double) views).isGreaterThan(0.55);
    }

    @Test
    void newcomersAreAMinorityButNotRareAndViewsStayWithinThirtyDaysOfPublishing() {
        ensureBackfill();

        long newcomers = count("SELECT COUNT(*) FROM employee WHERE community_join_date >= TIMESTAMP '2025-01-01 00:00:00'");
        assertThat(newcomers / (double) EMPLOYEES).isBetween(0.25, 0.65);
        assertThat(count("SELECT COUNT(*) FROM pv_log p JOIN content c ON c.id = p.content_id"
            + " WHERE p.view_time < c.publish_time OR p.view_time > c.publish_time + INTERVAL '31 days'")).isZero();
    }

    @Test
    void weekendsGetFarFewerViewsPerDayThanWeekdays() {
        ensureBackfill();

        // PostgreSQL 的 dow：0 = 周日，6 = 周六
        long weekend = count("SELECT COUNT(*) FROM pv_log WHERE EXTRACT(dow FROM view_time) IN (0, 6)");
        long weekday = count("SELECT COUNT(*) FROM pv_log WHERE EXTRACT(dow FROM view_time) NOT IN (0, 6)");
        assertThat(weekend / 2.0).isLessThan(weekday / 5.0 * 0.6);
    }
}
