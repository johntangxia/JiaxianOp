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
