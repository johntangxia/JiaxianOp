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
    private static final int EMPLOYEES = 100;
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
