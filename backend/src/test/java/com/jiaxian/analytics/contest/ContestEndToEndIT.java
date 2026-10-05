package com.jiaxian.analytics.contest;

import com.fasterxml.jackson.databind.JsonNode;
import com.jiaxian.analytics.simulator.HistoricalBackfillService;
import com.jiaxian.analytics.simulator.SimulationConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实回填一次，检查两个榜的整体性质：参赛频道数、分数范围、名次与分数一致、进步榜。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContestEndToEndIT {

    @Autowired private HistoricalBackfillService backfillService;
    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;

    private JsonNode get(String path) {
        return rest.getForEntity("http://localhost:" + port + path, JsonNode.class).getBody();
    }

    @Test
    void boardsAreConsistentAfterARealBackfill() {
        backfillService.runBackfill(SimulationConfig.builder()
            .batchId("contest-e2e").employeeCount(40).accountCount(16).teamAccountRatio(0.25).contentCount(150)
            .historyStart(LocalDate.of(2025, 1, 1)).historyEnd(LocalDate.of(2025, 7, 1)).randomSeed(31)
            .personas(false).build());

        JsonNode periods = get("/api/contest/periods?type=MONTH");
        assertThat(periods.size()).isGreaterThanOrEqualTo(6);
        assertThat(periods.get(0).get("complete").asBoolean()).isFalse();   // 最新的一个月还没过完（数据最后一天在其中）

        JsonNode boards = get("/api/contest/boards?type=MONTH&periodStart=2025-05-01");
        assertThat(boards.get("previousHasData").asBoolean()).isTrue();
        assertThat(boards.get("domain")).hasSize(23);
        assertThat(boards.get("composite")).hasSize(5);

        for (String league : new String[] {"domain", "composite"}) {
            JsonNode entries = boards.get(league);
            double previousScore = Double.MAX_VALUE;
            for (JsonNode entry : entries) {
                double score = entry.get("score").asDouble();
                assertThat(score).as(league).isBetween(0.0, 100.0);
                assertThat(score).as("榜单按分数从高到低").isLessThanOrEqualTo(previousScore);
                previousScore = score;
                // 名次 = 1 + 严格高于它的频道数
                long higher = 0;
                for (JsonNode other : entries) {
                    if (other.get("score").asDouble() > score) {
                        higher++;
                    }
                }
                assertThat(entry.get("rank").asInt()).as(entry.get("name").asText()).isEqualTo(higher + 1);
                assertThat(entry.get("rankChange").asInt())
                    .isEqualTo(entry.get("previousRank").asInt() - entry.get("rank").asInt());
            }
            assertThat(entries.get(0).get("rank").asInt()).isEqualTo(1);
        }

        // 直播没有内容，在综合榜里分数最低
        double liveScore = 100;
        for (JsonNode entry : boards.get("composite")) {
            if (entry.get("name").asText().equals("直播")) {
                liveScore = entry.get("score").asDouble();
            }
        }
        assertThat(liveScore).isZero();

        JsonNode improvers = boards.get("improvers");
        assertThat(improvers.size()).isLessThanOrEqualTo(5);
        for (JsonNode entry : improvers) {
            assertThat(entry.get("scoreChange").asDouble()).isPositive();
        }
    }
}
