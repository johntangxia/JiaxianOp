package com.jiaxian.analytics.channel;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 频道驾驶舱的聚合查询。所有方法的区间都是 [from, to] 两端含（按天），内部转成半开区间 [from 零点, to+1 天零点)。
 * 来源为空的旧浏览记录归入"其他"。
 */
@Repository
public class ChannelQueryRepository {

    public record ChannelRef(long id, String name, String category) {
    }

    /** 领域频道的内容被浏览的情况：PV、去重浏览人数和按来源的拆分。 */
    public record ContentSide(long pv, long uv, SourceMix mix) {
    }

    public record DailySource(LocalDate day, long ownEntry, long compositeEntry, long search, long share, long other) {
    }

    public record DailyEntry(LocalDate day, long impressions, long clicks) {
    }

    public record TopContent(long contentId, String type, String accountName, LocalDateTime publishTime, long pv,
                             long interactions) {
    }

    private final JdbcTemplate jdbc;

    public ChannelQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Timestamp start(LocalDate from) {
        return Timestamp.valueOf(from.atStartOfDay());
    }

    private static Timestamp end(LocalDate to) {
        return Timestamp.valueOf(to.plusDays(1).atStartOfDay());
    }

    private static LocalDate day(Timestamp timestamp) {
        return timestamp.toLocalDateTime().toLocalDate();
    }

    /** 综合频道和领域频道（不含已停用），按 id 排序。 */
    public List<ChannelRef> channels() {
        return jdbc.query("SELECT id, name, category FROM channel WHERE category IN ('综合频道', '领域频道') ORDER BY id",
            (rs, i) -> new ChannelRef(rs.getLong(1), rs.getString(2), rs.getString(3)));
    }

    /** 每个频道"自己入口的分发"：曝光、点击、触达/点击 UV、入口来源浏览的平均停留。键是入口频道 id。 */
    public Map<Long, DistributionMetrics> distribution(LocalDate from, LocalDate to) {
        Map<Long, Double> dwell = new HashMap<>();
        jdbc.query("SELECT entry_channel_id, AVG(dwell_seconds) FROM pv_log"
            + " WHERE entry_channel_id IS NOT NULL AND dwell_seconds IS NOT NULL AND view_time >= ? AND view_time < ?"
            + " GROUP BY entry_channel_id",
            rs -> {
                dwell.put(rs.getLong(1), rs.getDouble(2));
            }, start(from), end(to));

        Map<Long, DistributionMetrics> result = new HashMap<>();
        jdbc.query("SELECT entry_channel_id, COUNT(*), SUM(CASE WHEN clicked THEN 1 ELSE 0 END),"
            + " COUNT(DISTINCT viewer_employee_id),"
            + " COUNT(DISTINCT CASE WHEN clicked THEN viewer_employee_id END)"
            + " FROM impression WHERE impression_time >= ? AND impression_time < ? GROUP BY entry_channel_id",
            rs -> {
                long channelId = rs.getLong(1);
                long impressions = rs.getLong(2);
                long clicks = rs.getLong(3);
                result.put(channelId, new DistributionMetrics(impressions, clicks,
                    impressions == 0 ? null : clicks / (double) impressions, rs.getLong(4), rs.getLong(5),
                    dwell.get(channelId)));
            }, start(from), end(to));
        return result;
    }

    /** 每个领域频道的内容被浏览的情况；键是内容所属频道 id。 */
    public Map<Long, ContentSide> contentSide(LocalDate from, LocalDate to) {
        Map<Long, ContentSide> result = new HashMap<>();
        jdbc.query("SELECT c.channel_id, COUNT(*), COUNT(DISTINCT p.viewer_employee_id),"
            + " SUM(CASE WHEN p.source_type = 'ENTRY' AND p.entry_channel_id = c.channel_id THEN 1 ELSE 0 END),"
            + " SUM(CASE WHEN p.source_type = 'ENTRY' AND p.entry_channel_id <> c.channel_id THEN 1 ELSE 0 END),"
            + " SUM(CASE WHEN p.source_type = 'SEARCH' THEN 1 ELSE 0 END),"
            + " SUM(CASE WHEN p.source_type = 'SHARE' THEN 1 ELSE 0 END)"
            + " FROM pv_log p JOIN content c ON c.id = p.content_id"
            + " WHERE p.view_time >= ? AND p.view_time < ? GROUP BY c.channel_id",
            rs -> {
                long pv = rs.getLong(2);
                long own = rs.getLong(4);
                long composite = rs.getLong(5);
                long search = rs.getLong(6);
                long share = rs.getLong(7);
                result.put(rs.getLong(1), new ContentSide(pv, rs.getLong(3),
                    new SourceMix(own, composite, search, share, pv - own - composite - search - share)));
            }, start(from), end(to));
        return result;
    }

    /** 期间内发生在领域频道内容上的互动数：点赞、转发、收藏加评论（评论点赞不算）。键是内容所属频道 id。 */
    public Map<Long, Long> interactions(LocalDate from, LocalDate to) {
        Map<Long, Long> result = new HashMap<>();
        jdbc.query("SELECT c.channel_id, COUNT(*) FROM engagement_log g JOIN content c ON c.id = g.content_id"
            + " WHERE g.occur_time >= ? AND g.occur_time < ? GROUP BY c.channel_id",
            rs -> {
                result.merge(rs.getLong(1), rs.getLong(2), Long::sum);
            }, start(from), end(to));
        jdbc.query("SELECT c.channel_id, COUNT(*) FROM comment m JOIN content c ON c.id = m.content_id"
            + " WHERE m.create_time >= ? AND m.create_time < ? GROUP BY c.channel_id",
            rs -> {
                result.merge(rs.getLong(1), rs.getLong(2), Long::sum);
            }, start(from), end(to));
        return result;
    }

    /** 期间内发布的内容数和去重作者数。键是内容所属频道 id。 */
    public Map<Long, SupplyMetrics> supply(LocalDate from, LocalDate to) {
        Map<Long, SupplyMetrics> result = new HashMap<>();
        jdbc.query("SELECT c.channel_id, COUNT(DISTINCT c.id), COUNT(DISTINCT a.employee_id)"
            + " FROM content c LEFT JOIN content_author a ON a.content_id = c.id"
            + " WHERE c.publish_time >= ? AND c.publish_time < ? GROUP BY c.channel_id",
            rs -> {
                result.put(rs.getLong(1), new SupplyMetrics(rs.getLong(2), rs.getLong(3)));
            }, start(from), end(to));
        return result;
    }

    /** 每个频道里每篇内容的期间 PV，从高到低（用来算头部集中度）。键是内容所属频道 id。 */
    public Map<Long, List<Long>> contentPv(LocalDate from, LocalDate to) {
        Map<Long, List<Long>> result = new HashMap<>();
        jdbc.query("SELECT c.channel_id, COUNT(*) FROM pv_log p JOIN content c ON c.id = p.content_id"
            + " WHERE p.view_time >= ? AND p.view_time < ? GROUP BY c.channel_id, c.id"
            + " ORDER BY c.channel_id, COUNT(*) DESC",
            rs -> {
                result.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getLong(2));
            }, start(from), end(to));
        return result;
    }

    /** 综合入口的点击按内容所属领域拆分：入口频道 id → (领域频道 id → 点击数)。 */
    public Map<Long, Map<Long, Long>> compositeClicksByDomain(LocalDate from, LocalDate to) {
        Map<Long, Map<Long, Long>> result = new HashMap<>();
        jdbc.query("SELECT p.entry_channel_id, c.channel_id, COUNT(*) FROM pv_log p JOIN content c ON c.id = p.content_id"
            + " WHERE p.source_type = 'ENTRY' AND p.entry_channel_id <> c.channel_id"
            + " AND p.view_time >= ? AND p.view_time < ? GROUP BY p.entry_channel_id, c.channel_id",
            rs -> {
                result.computeIfAbsent(rs.getLong(1), k -> new LinkedHashMap<>()).put(rs.getLong(2), rs.getLong(3));
            }, start(from), end(to));
        return result;
    }

    // ---- 详情页 ----

    /** 一个领域频道的内容每天的 PV，按来源拆分；没有浏览的日子不出现。 */
    public List<DailySource> dailyContentPv(long channelId, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT date_trunc('day', p.view_time),"
            + " SUM(CASE WHEN p.source_type = 'ENTRY' AND p.entry_channel_id = c.channel_id THEN 1 ELSE 0 END),"
            + " SUM(CASE WHEN p.source_type = 'ENTRY' AND p.entry_channel_id <> c.channel_id THEN 1 ELSE 0 END),"
            + " SUM(CASE WHEN p.source_type = 'SEARCH' THEN 1 ELSE 0 END),"
            + " SUM(CASE WHEN p.source_type = 'SHARE' THEN 1 ELSE 0 END), COUNT(*)"
            + " FROM pv_log p JOIN content c ON c.id = p.content_id"
            + " WHERE c.channel_id = ? AND p.view_time >= ? AND p.view_time < ?"
            + " GROUP BY date_trunc('day', p.view_time) ORDER BY 1",
            (rs, i) -> {
                long own = rs.getLong(2);
                long composite = rs.getLong(3);
                long search = rs.getLong(4);
                long share = rs.getLong(5);
                return new DailySource(day(rs.getTimestamp(1)), own, composite, search, share,
                    rs.getLong(6) - own - composite - search - share);
            }, channelId, start(from), end(to));
    }

    /** 一个频道入口每天的曝光和点击；没有曝光的日子不出现。 */
    public List<DailyEntry> dailyEntry(long channelId, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT date_trunc('day', impression_time), COUNT(*),"
            + " SUM(CASE WHEN clicked THEN 1 ELSE 0 END) FROM impression"
            + " WHERE entry_channel_id = ? AND impression_time >= ? AND impression_time < ?"
            + " GROUP BY date_trunc('day', impression_time) ORDER BY 1",
            (rs, i) -> new DailyEntry(day(rs.getTimestamp(1)), rs.getLong(2), rs.getLong(3)),
            channelId, start(from), end(to));
    }

    /**
     * Top 内容：领域频道按内容的期间 PV；综合频道按该入口的点击数（PV 列即点击数）。
     * 互动数 = 期间内这篇内容上的点赞/转发/收藏加评论。limit 是调用方给的内部常量（整数，直接拼进 SQL）。
     */
    public List<TopContent> topContents(long channelId, boolean composite, LocalDate from, LocalDate to, int limit) {
        String filter = composite
            ? " AND p.source_type = 'ENTRY' AND p.entry_channel_id = ?"
            : " AND c.channel_id = ?";
        return jdbc.query("SELECT c.id, c.type, a.name, c.publish_time, COUNT(p.id),"
            + " (SELECT COUNT(*) FROM engagement_log g WHERE g.content_id = c.id AND g.occur_time >= ? AND g.occur_time < ?)"
            + " + (SELECT COUNT(*) FROM comment m WHERE m.content_id = c.id AND m.create_time >= ? AND m.create_time < ?)"
            + " FROM content c JOIN account a ON a.id = c.account_id JOIN pv_log p ON p.content_id = c.id"
            + " WHERE p.view_time >= ? AND p.view_time < ?" + filter
            + " GROUP BY c.id, c.type, a.name, c.publish_time ORDER BY COUNT(p.id) DESC, c.id LIMIT " + limit,
            (rs, i) -> new TopContent(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4).toLocalDateTime(), rs.getLong(5), rs.getLong(6)),
            start(from), end(to), start(from), end(to), start(from), end(to), channelId);
    }
}
