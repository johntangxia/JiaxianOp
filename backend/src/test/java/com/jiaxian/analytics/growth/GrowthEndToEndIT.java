package com.jiaxian.analytics.growth;

import com.fasterxml.jackson.databind.JsonNode;
import com.jiaxian.analytics.simulator.HistoricalBackfillService;
import com.jiaxian.analytics.simulator.SimulationConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 真实回填一次，然后对账部门渗透、告警重算的幂等性、热点内容和任务基线/当前值。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GrowthEndToEndIT {

    @Autowired private HistoricalBackfillService backfillService;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private GrowthAlertRepository alertRepository;
    @LocalServerPort private int port;

    private JsonNode get(String path) {
        return rest.getForEntity("http://localhost:" + port + path, JsonNode.class).getBody();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static Timestamp start(LocalDate day) {
        return Timestamp.valueOf(day.atStartOfDay());
    }

    private long channelPv(long channelId, LocalDate from, LocalDate to) {
        return count("SELECT COUNT(*) FROM pv_log p JOIN content c ON c.id = p.content_id WHERE c.channel_id = ?"
            + " AND p.view_time >= ? AND p.view_time < ?", channelId, start(from), start(to.plusDays(1)));
    }

    @Test
    void growthNumbersReconcileWithTheRawTables() {
        backfillService.runBackfill(SimulationConfig.builder()
            .batchId("growth-e2e").employeeCount(150).accountCount(15).teamAccountRatio(0.25).contentCount(250)
            .historyStart(LocalDate.of(2025, 1, 1)).historyEnd(LocalDate.of(2025, 7, 1)).randomSeed(61)
            .personas(true).build());
        LocalDate asOf = LocalDate.parse(get("/api/readers/range").get("to").asText());

        // 部门渗透：各部门成员之和 = 全社区成员 = 窗口结束日已加入的员工数；渗透率在 0–1
        JsonNode departments = get("/api/growth/departments?asOf=" + asOf + "&days=30");
        long members = 0;
        long active = 0;
        for (JsonNode row : departments.get("departments")) {
            members += row.get("current").get("members").asLong();
            active += row.get("current").get("active").asLong();
            if (!row.get("current").get("penetration").isNull()) {
                assertThat(row.get("current").get("penetration").asDouble()).isBetween(0.0, 1.0);
            }
        }
        assertThat(members).isEqualTo(departments.get("overall").get("current").get("members").asLong());
        assertThat(active).isEqualTo(departments.get("overall").get("current").get("active").asLong());
        assertThat(members).isEqualTo(count("SELECT COUNT(*) FROM employee WHERE community_join_date IS NULL OR community_join_date < ?",
            start(asOf.plusDays(1))));

        // 热点和潜力内容：满足各自的最低 PV 门槛
        JsonNode hot = get("/api/growth/hot-contents?asOf=" + asOf + "&limit=50");
        for (JsonNode item : hot.get("hot")) {
            assertThat(item.get("pv").asLong()).isGreaterThanOrEqualTo(10);
            assertThat(item.get("hotMultiple").asDouble()).isGreaterThanOrEqualTo(3.0);
        }
        for (JsonNode item : hot.get("potential")) {
            assertThat(item.get("pv").asLong()).isGreaterThanOrEqualTo(5);
            assertThat(item.get("qualityMultiple").asDouble()).isGreaterThanOrEqualTo(1.5);
        }

        // 告警重算：幂等；关闭的告警不会被重算重开
        JsonNode first = rest.postForEntity("http://localhost:" + port + "/api/admin/growth/alerts/rebuild?asOf=" + asOf, null, JsonNode.class).getBody();
        long afterFirst = alertRepository.count();
        assertThat(first.get("created").asInt()).isEqualTo((int) afterFirst);
        JsonNode second = rest.postForEntity("http://localhost:" + port + "/api/admin/growth/alerts/rebuild?asOf=" + asOf, null, JsonNode.class).getBody();
        assertThat(second.get("created").asInt()).isZero();
        assertThat(alertRepository.count()).isEqualTo(afterFirst);
        if (afterFirst > 0) {
            GrowthAlert any = alertRepository.findAll().get(0);
            any.setStatus(AlertStatus.CLOSED);
            alertRepository.save(any);
            rest.postForEntity("http://localhost:" + port + "/api/admin/growth/alerts/rebuild?asOf=" + asOf, null, JsonNode.class);
            assertThat(alertRepository.findById(any.getId()).orElseThrow().getStatus()).isEqualTo(AlertStatus.CLOSED);
        }

        // 任务：基线和当前值与手算的 PV 一致，进度按公式
        long channelId = count("SELECT c.channel_id FROM content c JOIN pv_log p ON p.content_id = c.id GROUP BY c.channel_id"
            + " ORDER BY COUNT(*) DESC LIMIT 1");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        JsonNode created = rest.exchange("http://localhost:" + port + "/api/admin/growth/tasks", HttpMethod.POST,
            new HttpEntity<>(Map.of("title", "端到端任务", "taskType", "PUSH_HOT_CONTENT", "entityType", "CHANNEL", "entityId", channelId,
                "metric", "PV", "targetValue", 100000.0, "startDate", asOf.toString()), headers), JsonNode.class).getBody();
        double expectedBaseline = channelPv(channelId, asOf.minusDays(30), asOf.minusDays(1));
        double expectedCurrent = channelPv(channelId, asOf.minusDays(29), asOf);
        assertThat(created.get("task").get("baselineValue").asDouble()).isEqualTo(expectedBaseline);
        assertThat(created.get("currentValue").asDouble()).isEqualTo(expectedCurrent);
        assertThat(created.get("progress").asDouble()).isCloseTo((expectedCurrent - expectedBaseline) / (100000.0 - expectedBaseline), within(1e-9));

        long taskId = created.get("task").get("id").asLong();
        JsonNode detail = get("/api/growth/tasks/" + taskId + "?asOf=" + asOf);
        assertThat(detail.get("series")).hasSize(8);
        assertThat(detail.get("series").get(7).get("value").asDouble()).isEqualTo(channelPv(channelId, asOf.minusDays(6), asOf));
        int marked = 0;
        for (JsonNode point : detail.get("series")) {
            marked += point.get("containsStart").asBoolean() ? 1 : 0;
        }
        assertThat(marked).isEqualTo(1);
    }
}
