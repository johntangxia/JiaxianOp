package com.jiaxian.analytics.simulator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 回填一次，然后检查来源、入口、曝光、停留、关注、搜索、标签这些行为数据的口径。 */
@SpringBootTest
class HistoricalBackfillBehaviorIT {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 1);
    private static boolean backfilled;

    @Autowired private HistoricalBackfillService backfillService;
    @Autowired private JdbcTemplate jdbc;

    private synchronized void ensureBackfill() {
        if (!backfilled) {
            backfillService.runBackfill(SimulationConfig.builder()
                .batchId("behavior-it").employeeCount(60).accountCount(24).teamAccountRatio(0.25)
                .contentCount(200).historyStart(START).historyEnd(END).randomSeed(12).personas(true).build());
            backfilled = true;
        }
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private double share(String where) {
        return count("SELECT COUNT(*) FROM pv_log WHERE " + where) / (double) count("SELECT COUNT(*) FROM pv_log");
    }

    @Test
    void everyViewHasASourceAndADwellAndOnlyEntryViewsHaveAnEntryChannel() {
        ensureBackfill();

        assertThat(count("SELECT COUNT(*) FROM pv_log WHERE source_type IS NULL OR dwell_seconds IS NULL OR completed IS NULL")).isZero();
        assertThat(count("SELECT COUNT(*) FROM pv_log WHERE source_type = 'ENTRY' AND entry_channel_id IS NULL")).isZero();
        assertThat(count("SELECT COUNT(*) FROM pv_log WHERE source_type <> 'ENTRY' AND entry_channel_id IS NOT NULL")).isZero();
        assertThat(count("SELECT COUNT(*) FROM pv_log WHERE dwell_seconds < 3 OR dwell_seconds > 900")).isZero();
    }

    @Test
    void sourceSharesMatchTheBusinessNumbers() {
        ensureBackfill();

        assertThat(share("source_type = 'SHARE'")).isBetween(0.34, 0.46);
        assertThat(share("source_type = 'OTHER'")).isBetween(0.27, 0.39);
        assertThat(share("source_type = 'SEARCH'")).isBetween(0.06, 0.14);
        String composite = "source_type = 'ENTRY' AND entry_channel_id IN (SELECT id FROM channel WHERE category = '综合频道')";
        assertThat(share(composite)).isBetween(0.10, 0.20);
        String own = "source_type = 'ENTRY' AND entry_channel_id = (SELECT channel_id FROM content c WHERE c.id = pv_log.content_id)";
        assertThat(share(own)).isBetween(0.0, 0.05);
    }

    @Test
    void entryChannelsFollowTheOwnVersusCompositeRules() {
        ensureBackfill();

        // 入口要么是内容自己的领域频道，要么是综合频道，没有别的领域的入口
        assertThat(count("SELECT COUNT(*) FROM pv_log p JOIN content c ON c.id = p.content_id"
            + " JOIN channel e ON e.id = p.entry_channel_id WHERE p.source_type = 'ENTRY'"
            + " AND e.category = '领域频道' AND e.id <> c.channel_id")).isZero();
        // 视频入口只分发视频
        assertThat(count("SELECT COUNT(*) FROM pv_log p JOIN content c ON c.id = p.content_id"
            + " JOIN channel e ON e.id = p.entry_channel_id WHERE e.name = '视频' AND c.type <> 'VIDEO'")).isZero();
        // 直播没有内容，不会有浏览
        assertThat(count("SELECT COUNT(*) FROM pv_log p JOIN channel e ON e.id = p.entry_channel_id WHERE e.name = '直播'")).isZero();
    }

    @Test
    void followEntryViewersWereFollowingTheContentsAccountAtThatTime() {
        ensureBackfill();

        long followViews = count("SELECT COUNT(*) FROM pv_log p JOIN channel e ON e.id = p.entry_channel_id WHERE e.name = '关注'");
        assertThat(followViews).isPositive();
        assertThat(count("SELECT COUNT(*) FROM pv_log p JOIN content c ON c.id = p.content_id"
            + " JOIN channel e ON e.id = p.entry_channel_id WHERE e.name = '关注' AND NOT EXISTS ("
            + " SELECT 1 FROM follow_relation f WHERE f.account_id = c.account_id"
            + " AND f.follower_employee_id = p.viewer_employee_id AND f.follow_time <= p.view_time"
            + " AND (f.unfollow_time IS NULL OR f.unfollow_time > p.view_time))")).isZero();
    }

    @Test
    void impressionsGiveEachEntryASensibleClickThroughRate() {
        ensureBackfill();

        // 每个入口点击对应一条已点击的曝光
        assertThat(count("SELECT COUNT(*) FROM impression WHERE clicked = TRUE"))
            .isEqualTo(count("SELECT COUNT(*) FROM pv_log WHERE source_type = 'ENTRY'"));
        assertThat(count("SELECT COUNT(*) FROM impression WHERE clicked = FALSE")).isGreaterThan(0);
        double recommendCtr = count("SELECT COUNT(*) FROM impression i JOIN channel e ON e.id = i.entry_channel_id"
            + " WHERE e.name = '推荐' AND i.clicked = TRUE")
            / (double) count("SELECT COUNT(*) FROM impression i JOIN channel e ON e.id = i.entry_channel_id WHERE e.name = '推荐'");
        assertThat(recommendCtr).isBetween(0.02, 0.08);
    }

    @Test
    void followsSearchesImpressionsAndTagsRespectTheJoinDate() {
        ensureBackfill();

        assertThat(count("SELECT COUNT(*) FROM follow_relation f JOIN employee e ON e.id = f.follower_employee_id"
            + " WHERE f.follow_time < e.community_join_date")).isZero();
        assertThat(count("SELECT COUNT(*) FROM follow_relation WHERE unfollow_time IS NOT NULL AND unfollow_time <= follow_time")).isZero();
        assertThat(count("SELECT COUNT(*) FROM search_log s JOIN employee e ON e.id = s.employee_id"
            + " WHERE s.search_time < e.community_join_date")).isZero();
        assertThat(count("SELECT COUNT(*) FROM impression i JOIN employee e ON e.id = i.viewer_employee_id"
            + " WHERE i.impression_time < e.community_join_date")).isZero();
        assertThat(count("SELECT COUNT(*) FROM follow_relation")).isGreaterThan(60);
    }

    @Test
    void searchesIncludeClickedAndUnclickedOnesAndEverySearchViewHasALog() {
        ensureBackfill();

        assertThat(count("SELECT COUNT(*) FROM search_log WHERE clicked_content_id IS NOT NULL"))
            .isEqualTo(count("SELECT COUNT(*) FROM pv_log WHERE source_type = 'SEARCH'"));
        assertThat(count("SELECT COUNT(*) FROM search_log WHERE clicked_content_id IS NULL")).isPositive();
    }

    @Test
    void everyContentIsTaggedWithItsDomainFirst() {
        ensureBackfill();

        assertThat(count("SELECT COUNT(*) FROM content c WHERE (SELECT COUNT(*) FROM content_tag t WHERE t.content_id = c.id) < 3")).isZero();
        assertThat(count("SELECT COUNT(*) FROM content c JOIN channel ch ON ch.id = c.channel_id WHERE NOT EXISTS ("
            + " SELECT 1 FROM content_tag t WHERE t.content_id = c.id AND t.tag = ch.name)")).isZero();
    }
}
