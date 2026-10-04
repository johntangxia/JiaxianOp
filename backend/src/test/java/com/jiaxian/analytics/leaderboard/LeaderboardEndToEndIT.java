package com.jiaxian.analytics.leaderboard;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 OpenGauss + 真实 HTTP：回填 → 全部重算快照 → 校验快照总和、快照榜 = 实时榜、级联清空。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaderboardEndToEndIT {

    @Autowired private HistoricalBackfillService backfillService;
    @Autowired private LeaderboardQueryRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void backfill() {
        backfillService.runBackfill(SimulationConfig.builder()
            .batchId("leaderboard-e2e")
            .employeeCount(30)
            .accountCount(12)
            .teamAccountRatio(0.25)
            .contentCount(60)
            .historyStart(LocalDate.of(2025, 1, 1))
            .historyEnd(LocalDate.of(2026, 3, 1))
            .randomSeed(21)
            .build());
    }

    private Map<String, Long> totals(String sql) {
        Map<String, Long> totals = new TreeMap<>();
        jdbc.query(sql, rs -> {
            totals.put(rs.getString(1), rs.getLong(2));
        });
        return totals;
    }

    /** 只保留已结束的周期（下一周期起点不晚于今天）。 */
    private Map<String, Long> endedOnly(PeriodType type, Map<String, Long> byPeriod) {
        LocalDate today = LocalDate.now();
        Map<String, Long> ended = new TreeMap<>();
        byPeriod.forEach((period, total) -> {
            if (!type.nextStart(type.parse(period)).isAfter(today)) {
                ended.put(period, total);
            }
        });
        return ended;
    }

    @Test
    void snapshotsAgreeWithTheLedgerAndTheLiveBoard() {
        backfill();

        ResponseEntity<JsonNode> rebuild = rest.postForEntity(
            url("/api/admin/analytics/leaderboard/snapshot"), null, JsonNode.class);
        assertThat(rebuild.getStatusCode().value()).isEqualTo(200);
        assertThat(rebuild.getBody().get("months").asInt()).isGreaterThan(0);
        assertThat(rebuild.getBody().get("years").asInt()).isGreaterThan(0);

        // 已结束周期的快照总和必须等于流水里同周期的总和（进行中的周期，比如当前这一年，按设计不落快照）
        assertThat(totals("SELECT period_value, SUM(points) FROM leaderboard_snapshot"
            + " WHERE period_type = 'MONTH' GROUP BY period_value"))
            .isEqualTo(endedOnly(PeriodType.MONTH,
                totals("SELECT to_char(occur_time, 'YYYY-MM'), SUM(points) FROM points_ledger GROUP BY 1")));
        assertThat(totals("SELECT period_value, SUM(points) FROM leaderboard_snapshot"
            + " WHERE period_type = 'YEAR' GROUP BY period_value"))
            .isEqualTo(endedOnly(PeriodType.YEAR,
                totals("SELECT to_char(occur_time, 'YYYY'), SUM(points) FROM points_ledger GROUP BY 1")));

        // 取快照人数最多的那个月：HTTP 返回的是快照，并且和实时榜逐行一致
        String busiestMonth = jdbc.queryForObject(
            "SELECT period_value FROM leaderboard_snapshot WHERE period_type = 'MONTH'"
                + " GROUP BY period_value ORDER BY COUNT(*) DESC, period_value LIMIT 1", String.class);
        JsonNode board = rest.getForEntity(
            url("/api/leaderboard/month?period=" + busiestMonth + "&limit=100"), JsonNode.class).getBody();
        assertThat(board.get("status").asText()).isEqualTo("ARCHIVED");
        assertThat(board.get("source").asText()).isEqualTo("SNAPSHOT");

        LocalDate start = PeriodType.MONTH.parse(busiestMonth);
        List<LeaderboardEntry> live = repository.live(start, PeriodType.MONTH.nextStart(start), 100);
        List<LeaderboardEntry> fromHttp = new ArrayList<>();
        for (JsonNode entry : board.get("entries")) {
            fromHttp.add(new LeaderboardEntry(entry.get("rank").asInt(), entry.get("employeeId").asLong(),
                entry.get("name").asText(), entry.get("points").asLong(),
                entry.get("activePoints").asLong(), entry.get("passivePoints").asLong()));
        }
        assertThat(live).isNotEmpty();
        assertThat(fromHttp).isEqualTo(live);

        // 排名并列时名次相同、名次不降序
        int previousRank = 0;
        for (LeaderboardEntry entry : fromHttp) {
            assertThat(entry.rank()).isGreaterThanOrEqualTo(previousRank);
            assertThat(entry.points()).isEqualTo(entry.activePoints() + entry.passivePoints());
            previousRank = entry.rank();
        }

        // 再次回填会 deleteAll() 全部员工：快照必须随员工级联清空
        backfill();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM leaderboard_snapshot", Long.class)).isZero();
    }
}
