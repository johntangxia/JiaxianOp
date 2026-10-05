package com.jiaxian.analytics.drill;

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

/** 真实回填一次，然后沿着频道 → 账号 → 博主 → 内容一路下钻，用 SQL 对账每一级的数字。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DrillEndToEndIT {

    // 浏览最晚延续到发布后 30 天，所以结束日取历史结束日之后一个多月，保证所有行为都在区间里
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

    private long pv(JsonNode metrics) {
        return metrics.get("content").get("pv").asLong();
    }

    @Test
    void everyLevelOfTheDrillPathReconcilesWithTheRawTables() {
        backfillService.runBackfill(SimulationConfig.builder()
            .batchId("drill-e2e").employeeCount(40).accountCount(16).teamAccountRatio(0.25).contentCount(120)
            .historyStart(LocalDate.of(2025, 1, 1)).historyEnd(LocalDate.of(2025, 7, 1)).randomSeed(41)
            .personas(false).build());

        // 频道 → 账号：该频道下各账号 PV 之和 = 频道 PV
        long channelId = count("SELECT c.channel_id FROM content c JOIN pv_log p ON p.content_id = c.id"
            + " GROUP BY c.channel_id ORDER BY COUNT(*) DESC LIMIT 1");
        long channelPv = count("SELECT COUNT(*) FROM pv_log p JOIN content c ON c.id = p.content_id WHERE c.channel_id = ?", channelId);
        JsonNode accounts = get("/api/drill/channels/" + channelId + "/accounts?" + RANGE);
        long accountsPv = 0;
        for (JsonNode row : accounts.get("accounts")) {
            accountsPv += pv(row.get("current"));
        }
        assertThat(accountsPv).isEqualTo(channelPv);

        // 账号：KPI = 各频道分布之和；趋势、构成都等于 PV；作者各自不超过账号 PV，合著使作者合计 ≥ 账号 PV
        JsonNode topAccountRow = accounts.get("accounts").get(0);
        long accountId = topAccountRow.get("id").asLong();
        JsonNode account = get("/api/drill/accounts/" + accountId + "?" + RANGE + "&granularity=MONTH");
        long accountPv = pv(account.get("current"));
        assertThat(accountPv).isEqualTo(count("SELECT COUNT(*) FROM pv_log p JOIN content c ON c.id = p.content_id WHERE c.account_id = ?", accountId));
        assertThat(sum(account.get("channels"))).isEqualTo(accountPv);
        assertThat(trendPv(account.get("trend"))).isEqualTo(accountPv);
        assertThat(compositionPv(account.get("composition"))).isEqualTo(accountPv);
        long authorsPv = 0;
        for (JsonNode author : account.get("authors")) {
            assertThat(pv(author.get("current"))).isLessThanOrEqualTo(accountPv);
            authorsPv += pv(author.get("current"));
        }
        assertThat(authorsPv).isGreaterThanOrEqualTo(accountPv);
        long followers = count("SELECT COUNT(*) FROM follow_relation WHERE account_id = ?"
            + " AND follow_time < TIMESTAMP '2025-10-01 00:00:00' AND (unfollow_time IS NULL OR unfollow_time >= TIMESTAMP '2025-10-01 00:00:00')", accountId);
        assertThat(account.get("current").get("followers").get("followers").asLong()).isEqualTo(followers);

        // 博主：按作者归属的 PV；各账号分布之和 = 博主 PV
        long bloggerId = account.get("authors").get(0).get("id").asLong();
        JsonNode blogger = get("/api/drill/bloggers/" + bloggerId + "?" + RANGE);
        long bloggerPv = pv(blogger.get("current"));
        assertThat(bloggerPv).isEqualTo(count("SELECT COUNT(*) FROM pv_log p JOIN content_author a ON a.content_id = p.content_id WHERE a.employee_id = ?", bloggerId));
        assertThat(sum(blogger.get("accounts"))).isEqualTo(bloggerPv);
        assertThat(sum(blogger.get("channels"))).isEqualTo(bloggerPv);
        assertThat(blogger.get("current").get("followers").isNull()).isTrue();
        assertThat(blogger.get("blogger").get("kolProfile").isBoolean()).isTrue();

        // 内容：入口曝光里的点击数 = 该内容入口来源的 PV
        long contentId = account.get("topContents").get(0).get("contentId").asLong();
        JsonNode content = get("/api/drill/contents/" + contentId + "?" + RANGE);
        long contentPv = pv(content.get("current"));
        assertThat(contentPv).isEqualTo(count("SELECT COUNT(*) FROM pv_log WHERE content_id = ?", contentId));
        long entryClicks = 0;
        for (JsonNode entry : content.get("entries")) {
            entryClicks += entry.get("clicks").asLong();
        }
        assertThat(entryClicks).isEqualTo(count("SELECT COUNT(*) FROM pv_log WHERE content_id = ? AND source_type = 'ENTRY'", contentId));
        assertThat(compositionPv(content.get("composition"))).isEqualTo(contentPv);
        assertThat(content.get("tags").size()).isGreaterThanOrEqualTo(3);
        assertThat(content.get("authors").size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void comparisonsReturnTheSameNumbersAsTheDrillPages() {
        backfillService.runBackfill(SimulationConfig.builder()
            .batchId("drill-e2e-2").employeeCount(40).accountCount(16).teamAccountRatio(0.25).contentCount(120)
            .historyStart(LocalDate.of(2025, 1, 1)).historyEnd(LocalDate.of(2025, 7, 1)).randomSeed(42)
            .personas(false).build());

        var topAccounts = jdbc.queryForList("SELECT c.account_id FROM content c JOIN pv_log p ON p.content_id = c.id"
            + " GROUP BY c.account_id ORDER BY COUNT(*) DESC LIMIT 2", Long.class);
        JsonNode comparison = get("/api/compare?type=ACCOUNT&ids=" + topAccounts.get(0) + "," + topAccounts.get(1)
            + "&" + RANGE + "&granularity=MONTH");

        assertThat(comparison.get("subjects")).hasSize(2);
        for (int i = 0; i < 2; i++) {
            JsonNode subject = comparison.get("subjects").get(i);
            JsonNode page = get("/api/drill/accounts/" + topAccounts.get(i) + "?" + RANGE + "&granularity=MONTH");
            assertThat(pv(subject.get("current"))).isEqualTo(pv(page.get("current")));
            assertThat(trendPv(subject.get("trend"))).isEqualTo(pv(subject.get("current")));
            assertThat(subject.get("current").get("followers").get("followers").asLong())
                .isEqualTo(page.get("current").get("followers").get("followers").asLong());
        }
    }

    private long sum(JsonNode rows) {
        long total = 0;
        for (JsonNode row : rows) {
            total += pv(row.get("current"));
        }
        return total;
    }

    private long trendPv(JsonNode trend) {
        long total = 0;
        for (JsonNode point : trend) {
            total += point.get("ownEntry").asLong() + point.get("compositeEntry").asLong() + point.get("search").asLong()
                + point.get("share").asLong() + point.get("other").asLong();
        }
        return total;
    }

    private long compositionPv(JsonNode composition) {
        long total = 0;
        for (JsonNode slice : composition) {
            total += slice.get("value").asLong();
        }
        return total;
    }
}
