package com.jiaxian.analytics.channel;

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

/** 真实回填一次，然后用 SQL 直接对账驾驶舱接口的数字。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChannelCockpitEndToEndIT {

    // 浏览最晚延续到发布后 30 天，所以结束日取历史结束日之后一个多月，保证所有行为都在区间里
    private static final String FROM = "2025-01-01";
    private static final String TO = "2025-09-30";

    @Autowired private HistoricalBackfillService backfillService;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @LocalServerPort private int port;

    private JsonNode get(String path) {
        return rest.getForEntity("http://localhost:" + port + path, JsonNode.class).getBody();
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void cockpitNumbersReconcileWithTheRawTables() {
        backfillService.runBackfill(SimulationConfig.builder()
            .batchId("cockpit-e2e").employeeCount(40).accountCount(16).teamAccountRatio(0.25).contentCount(100)
            .historyStart(LocalDate.of(2025, 1, 1)).historyEnd(LocalDate.of(2025, 7, 1)).randomSeed(21)
            .personas(false).build());

        JsonNode cockpit = get("/api/channels/cockpit?from=" + FROM + "&to=" + TO);
        assertThat(cockpit.get("composite")).hasSize(5);
        assertThat(cockpit.get("domain")).hasSize(23);

        long totalPv = count("SELECT COUNT(*) FROM pv_log");
        long entryPv = count("SELECT COUNT(*) FROM pv_log WHERE source_type = 'ENTRY'");
        long domainPv = 0;
        long publishedContents = 0;
        long ownEntryClicks = 0;
        for (JsonNode row : cockpit.get("domain")) {
            JsonNode current = row.get("current");
            JsonNode mix = current.get("content").get("sourceMix");
            long pv = current.get("content").get("pv").asLong();
            domainPv += pv;
            publishedContents += current.get("supply").get("contents").asLong();
            ownEntryClicks += mix.get("ownEntry").asLong();
            // 来源拆分加起来就是 PV；自主入口点击 = 自主入口的"已点击曝光"
            assertThat(mix.get("ownEntry").asLong() + mix.get("compositeEntry").asLong() + mix.get("search").asLong()
                + mix.get("share").asLong() + mix.get("other").asLong()).as(row.get("name").asText()).isEqualTo(pv);
            assertThat(current.get("distribution").get("clicks").asLong()).as(row.get("name").asText())
                .isEqualTo(mix.get("ownEntry").asLong());
        }
        assertThat(domainPv).isEqualTo(totalPv);
        assertThat(publishedContents).isEqualTo(100);

        long compositeClicks = 0;
        for (JsonNode row : cockpit.get("composite")) {
            compositeClicks += row.get("current").get("distribution").get("clicks").asLong();
        }
        assertThat(ownEntryClicks + compositeClicks).isEqualTo(entryPv);

        // 点击的曝光都对应一条入口来源的浏览
        assertThat(count("SELECT COUNT(*) FROM impression WHERE clicked = TRUE")).isEqualTo(entryPv);
        // 直播没有内容，不会有任何分发
        for (JsonNode row : cockpit.get("composite")) {
            if (row.get("name").asText().equals("直播")) {
                assertThat(row.get("current").get("distribution").get("impressions").asLong()).isZero();
            }
        }
    }

    @Test
    void detailTrendAndCompositionAddUpToTheRowPv() {
        backfillService.runBackfill(SimulationConfig.builder()
            .batchId("cockpit-e2e-2").employeeCount(40).accountCount(16).teamAccountRatio(0.25).contentCount(100)
            .historyStart(LocalDate.of(2025, 1, 1)).historyEnd(LocalDate.of(2025, 7, 1)).randomSeed(22)
            .personas(false).build());

        long channelId = jdbc.queryForObject("SELECT c.channel_id FROM content c JOIN pv_log p ON p.content_id = c.id"
            + " GROUP BY c.channel_id ORDER BY COUNT(*) DESC LIMIT 1", Long.class);
        JsonNode detail = get("/api/channels/" + channelId + "/detail?from=" + FROM + "&to=" + TO + "&granularity=MONTH");

        long rowPv = detail.get("row").get("current").get("content").get("pv").asLong();
        assertThat(rowPv).isPositive();
        long trendPv = 0;
        for (JsonNode point : detail.get("trend")) {
            trendPv += point.get("ownEntry").asLong() + point.get("compositeEntry").asLong() + point.get("search").asLong()
                + point.get("share").asLong() + point.get("other").asLong();
        }
        long compositionPv = 0;
        for (JsonNode slice : detail.get("composition")) {
            compositionPv += slice.get("value").asLong();
        }
        assertThat(trendPv).isEqualTo(rowPv);
        assertThat(compositionPv).isEqualTo(rowPv);
        assertThat(detail.get("topContents").size()).isBetween(1, 10);
        assertThat(detail.get("topContents").get(0).get("pv").asLong()).isLessThanOrEqualTo(rowPv);
    }
}
