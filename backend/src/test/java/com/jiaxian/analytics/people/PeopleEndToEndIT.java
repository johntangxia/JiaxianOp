package com.jiaxian.analytics.people;

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
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实回填一次，然后用 SQL 和接口之间互相对账创作者与用户分析的各项数字。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PeopleEndToEndIT {

    private static final String RANGE = "from=2025-01-01&to=2025-09-30";
    private static final String SQL_RANGE = " >= TIMESTAMP '2025-01-01 00:00:00' AND %s < TIMESTAMP '2025-10-01 00:00:00'";

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

    private static long sum(JsonNode array, String field) {
        long total = 0;
        for (JsonNode item : array) {
            total += item.get(field).asLong();
        }
        return total;
    }

    @Test
    void creatorAndUserNumbersReconcile() {
        backfillService.runBackfill(SimulationConfig.builder()
            .batchId("people-e2e").employeeCount(100).accountCount(20).teamAccountRatio(0.25).contentCount(160)
            .historyStart(LocalDate.of(2025, 1, 1)).historyEnd(LocalDate.of(2025, 7, 1)).randomSeed(77)
            .personas(true).build());

        // ---- 创作者 ----
        long creatorsInSql = count("SELECT COUNT(DISTINCT employee_id) FROM active_action_log WHERE action_type = 'POST'"
            + " AND occur_time" + SQL_RANGE.formatted("occur_time"));
        assertThat(creatorsInSql).isPositive();

        JsonNode overview = get("/api/creators/overview?" + RANGE + "&granularity=MONTH");
        assertThat(overview.get("summary").get("creators").asLong()).isEqualTo(creatorsInSql);
        assertThat(overview.get("summary").get("posts").asLong()).isEqualTo(count("SELECT COUNT(*) FROM active_action_log"
            + " WHERE action_type = 'POST' AND occur_time" + SQL_RANGE.formatted("occur_time")));
        assertThat(sum(overview.get("series"), "posts")).isEqualTo(overview.get("summary").get("posts").asLong());
        assertThat(sum(overview.get("series"), "newCreators")).isEqualTo(overview.get("summary").get("newCreators").asLong());
        for (JsonNode cohort : overview.get("cohorts")) {
            for (JsonNode value : cohort.get("retention")) {
                if (!value.isNull()) {
                    assertThat(value.asDouble()).isBetween(0.0, 1.0);
                }
            }
        }

        JsonNode ranking = get("/api/creators/ranking?" + RANGE + "&sort=POSTS&limit=100");
        assertThat(ranking.get("total").asLong()).isEqualTo(creatorsInSql);
        for (JsonNode row : ranking.get("creators")) {
            long id = row.get("employeeId").asLong();
            assertThat(row.get("posts").asLong()).as("posts of %d", id).isPositive().isEqualTo(count(
                "SELECT COUNT(*) FROM active_action_log WHERE employee_id = ? AND action_type = 'POST' AND occur_time"
                    + SQL_RANGE.formatted("occur_time"), id));
            assertThat(row.get("pv").asLong()).as("pv of %d", id).isEqualTo(count("SELECT COUNT(*) FROM pv_log p"
                + " JOIN content_author ca ON ca.content_id = p.content_id WHERE ca.employee_id = ? AND p.view_time"
                + SQL_RANGE.formatted("p.view_time"), id));
        }
        long previousPosts = Long.MAX_VALUE;
        for (JsonNode row : ranking.get("creators")) {
            assertThat(row.get("posts").asLong()).isLessThanOrEqualTo(previousPosts);
            previousPosts = row.get("posts").asLong();
        }

        JsonNode concentration = get("/api/creators/concentration?" + RANGE);
        assertThat(concentration.get("creators").asLong()).isEqualTo(creatorsInSql);
        double previousShare = 0;
        for (JsonNode share : concentration.get("shares")) {
            if (!share.get("pvShare").isNull()) {
                assertThat(share.get("pvShare").asDouble()).isBetween(0.0, 1.0);
            }
        }
        assertThat(concentration.get("shares").get(0).get("pvShare").asDouble())
            .isLessThanOrEqualTo(concentration.get("shares").get(1).get("pvShare").asDouble());
        assertThat(concentration.get("gini").asDouble()).isBetween(0.0, 1.0);
        assertThat(sum(concentration.get("accountTypes"), "posts")).isEqualTo(count("SELECT COUNT(*) FROM content WHERE publish_time"
            + SQL_RANGE.formatted("publish_time")));

        // ---- 用户 ----
        JsonNode lifecycle = get("/api/users/lifecycle");
        LocalDate asOf = LocalDate.parse(lifecycle.get("asOf").asText());
        long members = count("SELECT COUNT(*) FROM employee WHERE community_join_date IS NULL OR community_join_date < ?",
            java.sql.Timestamp.valueOf(asOf.plusDays(1).atStartOfDay()));
        assertThat(lifecycle.get("members").asLong()).isEqualTo(members);
        assertThat(sum(lifecycle.get("stages"), "count")).isEqualTo(members);
        assertThat(sum(lifecycle.get("stages"), "previousCount")).isEqualTo(lifecycle.get("previousMembers").asLong());
        JsonNode transitions = lifecycle.get("transitions");
        assertThat(sum(transitions, "total")).isEqualTo(members);
        for (int i = 0; i < 6; i++) {          // 每个阶段一行：行合计 = 上个时点该阶段的人数
            assertThat(transitions.get(i).get("total").asLong()).isEqualTo(lifecycle.get("stages").get(i).get("previousCount").asLong());
        }
        for (int column = 0; column < 6; column++) {        // 每列合计 = 现在该阶段的人数
            long columnTotal = 0;
            for (JsonNode row : transitions) {
                columnTotal += row.get("to").get(column).get("count").asLong();
            }
            assertThat(columnTotal).isEqualTo(lifecycle.get("stages").get(column).get("count").asLong());
        }

        JsonNode value = get("/api/users/value");
        assertThat(value.get("members").asLong()).isEqualTo(members);
        assertThat(sum(value.get("segments"), "count")).isEqualTo(members);
        assertThat(value.get("segments").get(0).get("avgEngagement").asDouble())
            .isGreaterThanOrEqualTo(value.get("segments").get(4).get("avgEngagement").asDouble());

        // 未激活的人从来没有任何行为；新成员的加入日在最近 30 天内
        JsonNode dormant = get("/api/users/list?stage=DORMANT&limit=100");
        assertThat(dormant.get("total").asLong()).isEqualTo(lifecycle.get("stages").get(5).get("count").asLong());
        for (JsonNode user : dormant.get("users")) {
            assertThat(count("SELECT COUNT(*) FROM active_action_log WHERE employee_id = ?", user.get("employeeId").asLong())).isZero();
        }
        JsonNode fresh = get("/api/users/list?stage=NEW&limit=100");
        for (JsonNode user : fresh.get("users")) {
            assertThat(LocalDate.parse(user.get("joinDate").asText())).isAfterOrEqualTo(asOf.minusDays(29));
        }

        // 列表总数 = 成员数；用户 360 的阶段和分层与列表一致
        JsonNode list = get("/api/users/list?limit=100&sort=ACTIVE_DAYS");
        assertThat(list.get("total").asLong()).isEqualTo(members);
        Set<String> seen = new HashSet<>();
        for (JsonNode user : list.get("users")) {
            if (seen.size() >= 5) {
                break;
            }
            long id = user.get("employeeId").asLong();
            JsonNode profile = get("/api/users/" + id);
            assertThat(profile.get("basic").get("stage").asText()).isEqualTo(user.get("stage").asText());
            assertThat(profile.get("basic").get("valueSegment").asText()).isEqualTo(user.get("valueSegment").asText());
            assertThat(profile.get("timeline")).hasSize(12);
            assertThat(profile.get("hasPosts").asBoolean()).isEqualTo(count("SELECT COUNT(*) FROM active_action_log"
                + " WHERE employee_id = ? AND action_type = 'POST' AND occur_time < ?", id,
                java.sql.Timestamp.valueOf(asOf.plusDays(1).atStartOfDay())) > 0);
            seen.add(user.get("name").asText());
        }

        JsonNode interests = get("/api/users/interests?days=180");
        assertThat(sum(interests.get("depts"), "members")).isEqualTo(members);
        for (JsonNode dept : interests.get("depts")) {
            if (!dept.get("ownShare").isNull()) {
                assertThat(dept.get("ownShare").asDouble()).isBetween(0.0, 1.0);
            }
        }

        assertThat(get("/api/creators/ladder").get("members").asLong()).isEqualTo(members);
        assertThat(sum(get("/api/creators/ladder").get("stages"), "count")).isEqualTo(members);
    }
}
