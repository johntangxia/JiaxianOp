package com.jiaxian.analytics.reader;

import com.fasterxml.jackson.databind.JsonNode;
import com.jiaxian.analytics.simulator.HistoricalBackfillService;
import com.jiaxian.analytics.simulator.SimulationConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实回填一次（带沉默读者和新成员），然后用 SQL 对账读者侧的各项数字。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReaderEndToEndIT {

    private static final String RANGE = "from=2025-01-01&to=2025-09-30";

    @Autowired private HistoricalBackfillService backfillService;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @LocalServerPort private int port;

    private JsonNode get(String path) {
        return rest.getForEntity("http://localhost:" + port + path, JsonNode.class).getBody();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void readerNumbersReconcileWithTheRawTables() {
        backfillService.runBackfill(SimulationConfig.builder()
            .batchId("reader-e2e").employeeCount(80).accountCount(16).teamAccountRatio(0.25).contentCount(120)
            .historyStart(LocalDate.of(2025, 1, 1)).historyEnd(LocalDate.of(2025, 7, 1)).randomSeed(51)
            .personas(true).build());

        // 活跃：趋势里每天的 DAU 不超过当天的成员数，总活跃人次等于去重后的 (员工, 日) 数
        JsonNode activity = get("/api/readers/activity?" + RANGE + "&granularity=DAY");
        long activeDaysInSeries = 0;
        for (JsonNode point : activity.get("points")) {
            assertThat(point.get("activeUsers").asLong()).isLessThanOrEqualTo(point.get("members").asLong());
            activeDaysInSeries += point.get("activeUsers").asLong();
        }
        assertThat(activeDaysInSeries).isEqualTo(count("SELECT COUNT(*) FROM (SELECT DISTINCT employee_id,"
            + " date_trunc('day', occur_time) FROM active_action_log WHERE occur_time >= TIMESTAMP '2025-01-01 00:00:00'"
            + " AND occur_time < TIMESTAMP '2025-10-01 00:00:00') t"));
        assertThat(activity.get("summary").get("mau").asLong()).isGreaterThanOrEqualTo(activity.get("summary").get("wau").asLong());
        assertThat(activity.get("summary").get("wau").asLong()).isGreaterThanOrEqualTo(activity.get("summary").get("dau").asLong());

        // 同期群：各同期群人数之和 = 区间内加入的成员数；留存率在 0–1 之间，且 k=0 的留存按定义不为空
        JsonNode retention = get("/api/readers/retention?granularity=MONTH&" + RANGE);
        long cohortMembers = 0;
        for (JsonNode cohort : retention.get("cohorts")) {
            cohortMembers += cohort.get("size").asLong();
            for (JsonNode value : cohort.get("retention")) {
                if (!value.isNull()) {
                    assertThat(value.asDouble()).isBetween(0.0, 1.0);
                }
            }
        }
        assertThat(cohortMembers).isEqualTo(count("SELECT COUNT(*) FROM employee WHERE community_join_date >= TIMESTAMP '2025-01-01 00:00:00'"
            + " AND community_join_date < TIMESTAMP '2025-10-01 00:00:00'"));

        // 漏斗：加入人数 = 同期群人数之和；每一步不超过加入人数
        JsonNode funnel = get("/api/readers/funnel?" + RANGE);
        assertThat(funnel.get("joined").asLong()).isEqualTo(cohortMembers);
        for (JsonNode step : funnel.get("steps")) {
            assertThat(step.get("count").asLong()).isLessThanOrEqualTo(cohortMembers);
        }

        // 分层：五类之和 = 成员数；沉默读者名单里没有任何人发过帖或互动过，名单总数 = 分层里的沉默读者数
        JsonNode segments = get("/api/readers/segments?" + RANGE);
        long sum = 0;
        long lurkerSegment = 0;
        for (JsonNode segment : segments.get("segments")) {
            sum += segment.get("count").asLong();
            if (segment.get("role").asText().equals("LURKER")) {
                lurkerSegment = segment.get("count").asLong();
            }
        }
        assertThat(sum).isEqualTo(segments.get("members").asLong());
        assertThat(lurkerSegment).isPositive();

        JsonNode lurkers = get("/api/readers/lurkers?" + RANGE + "&limit=100");
        assertThat(lurkers.get("total").asLong()).isEqualTo(lurkerSegment);
        assertThat(lurkers.get("lurkers").size()).isEqualTo((int) Math.min(100, lurkerSegment));
        for (JsonNode lurker : lurkers.get("lurkers")) {
            long id = lurker.get("employeeId").asLong();
            assertThat(count("SELECT COUNT(*) FROM active_action_log WHERE employee_id = ? AND action_type IN"
                + " ('POST', 'LIKE', 'COMMENT', 'SHARE', 'FAVORITE')", id)).as("lurker %d interacted", id).isZero();
            assertThat(count("SELECT COUNT(*) FROM comment WHERE employee_id = ?", id)).isZero();
            assertThat(lurker.get("name").asText()).isNotBlank();
        }
    }
}
