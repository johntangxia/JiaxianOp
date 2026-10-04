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
