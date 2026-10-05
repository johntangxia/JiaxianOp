package com.jiaxian.analytics.drill;

import com.jiaxian.analytics.channel.ChannelQueryRepository.ContentSide;
import com.jiaxian.analytics.channel.ChannelQueryRepository.DailyEntry;
import com.jiaxian.analytics.channel.ChannelQueryRepository.DailySource;
import com.jiaxian.analytics.channel.ChannelQueryRepository.TopContent;
import com.jiaxian.analytics.channel.SourceMix;
import com.jiaxian.analytics.channel.SupplyMetrics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 下钻与对比的聚合查询：按分组键（频道/账号/作者/内容）参数化，支持按频道/账号/作者/键过滤。
 * 区间两端含（按天），内部转成半开区间；来源为空的旧浏览记入"其他"。
 * SQL 的分组列和连接只来自 {@link SubjectType} 的固定片段，过滤值都走绑定参数。
 */
@Repository
public class SubjectQueryRepository {

    public record AccountInfo(long id, String name, String type) {
    }

    public record PersonInfo(long id, String name) {
    }

    public record BloggerInfo(long id, String name, String dept, LocalDate communityJoinDate, boolean kolProfile) {
    }

    public record ContentInfo(long id, String type, long channelId, String channelName, long accountId,
                              String accountName, LocalDateTime publishTime) {
    }

    public record ChannelInfo(long id, String name, String category) {
    }

    public record DailyFollow(LocalDate day, long newFollowers, long lostFollowers) {
    }

    public record EntryExposure(long channelId, String channelName, String category, long impressions, long clicks) {
    }

    /** 查询语句的 FROM/WHERE 片段和绑定参数。 */
    private record Query(String from, String where, List<Object> params) {
    }

    private final JdbcTemplate jdbc;

    public SubjectQueryRepository(JdbcTemplate jdbc) {
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

    private static boolean needsAuthorJoin(SubjectType type, SubjectFilter filter) {
        return type == SubjectType.BLOGGER || filter.authorId() != null;
    }

    /**
     * 以 content c 为核心的 FROM/WHERE：fact 是事实表别名和它的 join 条件（如 "pv_log p ON p.content_id = c.id"），
     * timeColumn 是事实表上的时间列。结果里已经带上时间区间和各项过滤。
     */
    private Query query(SubjectType type, SubjectFilter filter, String factTable, String factAlias, String factJoinColumn,
                        String timeColumn, LocalDate from, LocalDate to) {
        StringBuilder fromSql = new StringBuilder(factTable + " " + factAlias + " JOIN content c ON c.id = "
            + factAlias + "." + factJoinColumn);
        if (needsAuthorJoin(type, filter)) {
            fromSql.append(" JOIN content_author ca ON ca.content_id = c.id");
        }
        StringBuilder where = new StringBuilder(" WHERE " + factAlias + "." + timeColumn + " >= ? AND " + factAlias + "."
            + timeColumn + " < ?");
        List<Object> params = new ArrayList<>(List.of(start(from), end(to)));
        appendFilters(type, filter, where, params);
        return new Query(fromSql.toString(), where.toString(), params);
    }

    private void appendFilters(SubjectType type, SubjectFilter filter, StringBuilder where, List<Object> params) {
        if (filter.channelId() != null) {
            where.append(" AND c.channel_id = ?");
            params.add(filter.channelId());
        }
        if (filter.accountId() != null) {
            where.append(" AND c.account_id = ?");
            params.add(filter.accountId());
        }
        if (filter.authorId() != null) {
            where.append(" AND ca.employee_id = ?");
            params.add(filter.authorId());
        }
        if (filter.ids() != null) {
            if (filter.ids().isEmpty()) {
                where.append(" AND 1 = 0");
            } else {
                where.append(" AND ").append(type.keyColumn()).append(" IN (")
                    .append(String.join(",", java.util.Collections.nCopies(filter.ids().size(), "?"))).append(")");
                params.addAll(filter.ids());
            }
        }
    }

    // ---- 内容侧：PV、互动、供给、集中度 ----

    public Map<Long, ContentSide> contentSide(SubjectType type, SubjectFilter filter, LocalDate from, LocalDate to) {
        Query q = query(type, filter, "pv_log", "p", "content_id", "view_time", from, to);
        Map<Long, ContentSide> result = new HashMap<>();
        jdbc.query("SELECT " + type.keyColumn() + ", COUNT(*), COUNT(DISTINCT p.viewer_employee_id),"
            + " SUM(CASE WHEN p.source_type = 'ENTRY' AND p.entry_channel_id = c.channel_id THEN 1 ELSE 0 END),"
            + " SUM(CASE WHEN p.source_type = 'ENTRY' AND p.entry_channel_id <> c.channel_id THEN 1 ELSE 0 END),"
            + " SUM(CASE WHEN p.source_type = 'SEARCH' THEN 1 ELSE 0 END),"
            + " SUM(CASE WHEN p.source_type = 'SHARE' THEN 1 ELSE 0 END)"
            + " FROM " + q.from() + q.where() + " GROUP BY " + type.keyColumn(),
            rs -> {
                long pv = rs.getLong(2);
                long own = rs.getLong(4);
                long composite = rs.getLong(5);
                long search = rs.getLong(6);
                long share = rs.getLong(7);
                result.put(rs.getLong(1), new ContentSide(pv, rs.getLong(3),
                    new SourceMix(own, composite, search, share, pv - own - composite - search - share)));
            }, q.params().toArray());
        return result;
    }

    /** 期间内的互动数：点赞、转发、收藏加评论（评论点赞不算）。 */
    public Map<Long, Long> interactions(SubjectType type, SubjectFilter filter, LocalDate from, LocalDate to) {
        Map<Long, Long> result = new HashMap<>();
        Query engagement = query(type, filter, "engagement_log", "g", "content_id", "occur_time", from, to);
        jdbc.query("SELECT " + type.keyColumn() + ", COUNT(*) FROM " + engagement.from() + engagement.where()
            + " GROUP BY " + type.keyColumn(), rs -> {
                result.merge(rs.getLong(1), rs.getLong(2), Long::sum);
            }, engagement.params().toArray());
        Query comments = query(type, filter, "comment", "m", "content_id", "create_time", from, to);
        jdbc.query("SELECT " + type.keyColumn() + ", COUNT(*) FROM " + comments.from() + comments.where()
            + " GROUP BY " + type.keyColumn(), rs -> {
                result.merge(rs.getLong(1), rs.getLong(2), Long::sum);
            }, comments.params().toArray());
        return result;
    }

    /** 期间内发布的内容数和去重作者数。 */
    public Map<Long, SupplyMetrics> supply(SubjectType type, SubjectFilter filter, LocalDate from, LocalDate to) {
        StringBuilder fromSql = new StringBuilder("content c");
        if (needsAuthorJoin(type, filter)) {
            fromSql.append(" JOIN content_author ca ON ca.content_id = c.id");
        }
        fromSql.append(" LEFT JOIN content_author a ON a.content_id = c.id");
        StringBuilder where = new StringBuilder(" WHERE c.publish_time >= ? AND c.publish_time < ?");
        List<Object> params = new ArrayList<>(List.of(start(from), end(to)));
        appendFilters(type, filter, where, params);
        Map<Long, SupplyMetrics> result = new HashMap<>();
        jdbc.query("SELECT " + type.keyColumn() + ", COUNT(DISTINCT c.id), COUNT(DISTINCT a.employee_id) FROM "
            + fromSql + where + " GROUP BY " + type.keyColumn(), rs -> {
                result.put(rs.getLong(1), new SupplyMetrics(rs.getLong(2), rs.getLong(3)));
            }, params.toArray());
        return result;
    }

    /** 每个分组里每篇内容的期间 PV，从高到低（用来算头部集中度）。 */
    public Map<Long, List<Long>> contentPv(SubjectType type, SubjectFilter filter, LocalDate from, LocalDate to) {
        Query q = query(type, filter, "pv_log", "p", "content_id", "view_time", from, to);
        Map<Long, List<Long>> result = new HashMap<>();
        jdbc.query("SELECT " + type.keyColumn() + ", COUNT(*) FROM " + q.from() + q.where()
            + " GROUP BY " + type.keyColumn() + ", c.id ORDER BY " + type.keyColumn() + ", COUNT(*) DESC", rs -> {
                result.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getLong(2));
            }, q.params().toArray());
        return result;
    }

    // ---- 曝光与停留 ----

    public Map<Long, ExposureMetrics> exposure(SubjectType type, SubjectFilter filter, LocalDate from, LocalDate to) {
        Map<Long, long[]> impressions = new HashMap<>();
        Query imp = query(type, filter, "impression", "i", "content_id", "impression_time", from, to);
        jdbc.query("SELECT " + type.keyColumn() + ", COUNT(*), SUM(CASE WHEN i.clicked THEN 1 ELSE 0 END) FROM "
            + imp.from() + imp.where() + " GROUP BY " + type.keyColumn(), rs -> {
                impressions.put(rs.getLong(1), new long[] {rs.getLong(2), rs.getLong(3)});
            }, imp.params().toArray());

        Map<Long, double[]> dwell = new HashMap<>();
        Query pv = query(type, filter, "pv_log", "p", "content_id", "view_time", from, to);
        jdbc.query("SELECT " + type.keyColumn() + ", AVG(p.dwell_seconds), AVG(CASE WHEN p.completed THEN 1.0 ELSE 0.0 END) FROM "
            + pv.from() + pv.where() + " AND p.dwell_seconds IS NOT NULL GROUP BY " + type.keyColumn(), rs -> {
                dwell.put(rs.getLong(1), new double[] {rs.getDouble(2), rs.getDouble(3)});
            }, pv.params().toArray());

        Map<Long, ExposureMetrics> result = new HashMap<>();
        Collection<Long> keys = new java.util.HashSet<>(impressions.keySet());
        keys.addAll(dwell.keySet());
        for (long key : keys) {
            long[] i = impressions.get(key);
            double[] d = dwell.get(key);
            long shown = i == null ? 0 : i[0];
            long clicked = i == null ? 0 : i[1];
            result.put(key, new ExposureMetrics(shown, clicked, shown == 0 ? null : clicked / (double) shown,
                d == null ? null : d[0], d == null ? null : d[1]));
        }
        return result;
    }

    // ---- 关注者（只有账号有） ----

    public Map<Long, FollowerMetrics> followers(Collection<Long> accountIds, LocalDate from, LocalDate to) {
        Map<Long, FollowerMetrics> result = new HashMap<>();
        if (accountIds.isEmpty()) {
            return result;
        }
        String in = String.join(",", java.util.Collections.nCopies(accountIds.size(), "?"));
        Timestamp rangeStart = start(from);
        Timestamp rangeEnd = end(to);
        Map<Long, long[]> counts = new HashMap<>();
        List<Object> base = new ArrayList<>(accountIds);

        List<Object> activeParams = new ArrayList<>(List.of(rangeEnd, rangeEnd));
        activeParams.addAll(base);
        jdbc.query("SELECT account_id, COUNT(*) FROM follow_relation WHERE follow_time < ?"
            + " AND (unfollow_time IS NULL OR unfollow_time >= ?) AND account_id IN (" + in + ") GROUP BY account_id",
            rs -> {
                counts.computeIfAbsent(rs.getLong(1), k -> new long[3])[0] = rs.getLong(2);
            }, activeParams.toArray());

        List<Object> newParams = new ArrayList<>(List.of(rangeStart, rangeEnd));
        newParams.addAll(base);
        jdbc.query("SELECT account_id, COUNT(*) FROM follow_relation WHERE follow_time >= ? AND follow_time < ?"
            + " AND account_id IN (" + in + ") GROUP BY account_id", rs -> {
                counts.computeIfAbsent(rs.getLong(1), k -> new long[3])[1] = rs.getLong(2);
            }, newParams.toArray());

        List<Object> lostParams = new ArrayList<>(List.of(rangeStart, rangeEnd));
        lostParams.addAll(base);
        jdbc.query("SELECT account_id, COUNT(*) FROM follow_relation WHERE unfollow_time >= ? AND unfollow_time < ?"
            + " AND account_id IN (" + in + ") GROUP BY account_id", rs -> {
                counts.computeIfAbsent(rs.getLong(1), k -> new long[3])[2] = rs.getLong(2);
            }, lostParams.toArray());

        counts.forEach((id, c) -> result.put(id, new FollowerMetrics(c[0], c[1], c[2])));
        return result;
    }

    /** 一个账号每天的新增/取关关注者；没有变化的日子不出现。 */
    public List<DailyFollow> dailyFollows(long accountId, LocalDate from, LocalDate to) {
        Map<LocalDate, long[]> byDay = new TreeMap<>();
        jdbc.query("SELECT date_trunc('day', follow_time), COUNT(*) FROM follow_relation WHERE account_id = ?"
            + " AND follow_time >= ? AND follow_time < ? GROUP BY date_trunc('day', follow_time)", rs -> {
                byDay.computeIfAbsent(day(rs.getTimestamp(1)), k -> new long[2])[0] = rs.getLong(2);
            }, accountId, start(from), end(to));
        jdbc.query("SELECT date_trunc('day', unfollow_time), COUNT(*) FROM follow_relation WHERE account_id = ?"
            + " AND unfollow_time >= ? AND unfollow_time < ? GROUP BY date_trunc('day', unfollow_time)", rs -> {
                byDay.computeIfAbsent(day(rs.getTimestamp(1)), k -> new long[2])[1] = rs.getLong(2);
            }, accountId, start(from), end(to));
        List<DailyFollow> result = new ArrayList<>();
        byDay.forEach((d, c) -> result.add(new DailyFollow(d, c[0], c[1])));
        return result;
    }

    // ---- 趋势 ----

    /** 一个对象的内容每天的 PV，按来源拆分；没有浏览的日子不出现。 */
    public List<DailySource> dailySource(SubjectType type, long id, LocalDate from, LocalDate to) {
        Query q = query(type, SubjectFilter.ofIds(id), "pv_log", "p", "content_id", "view_time", from, to);
        return jdbc.query("SELECT date_trunc('day', p.view_time),"
            + " SUM(CASE WHEN p.source_type = 'ENTRY' AND p.entry_channel_id = c.channel_id THEN 1 ELSE 0 END),"
            + " SUM(CASE WHEN p.source_type = 'ENTRY' AND p.entry_channel_id <> c.channel_id THEN 1 ELSE 0 END),"
            + " SUM(CASE WHEN p.source_type = 'SEARCH' THEN 1 ELSE 0 END),"
            + " SUM(CASE WHEN p.source_type = 'SHARE' THEN 1 ELSE 0 END), COUNT(*)"
            + " FROM " + q.from() + q.where() + " GROUP BY date_trunc('day', p.view_time) ORDER BY 1",
            (rs, i) -> {
                long own = rs.getLong(2);
                long composite = rs.getLong(3);
                long search = rs.getLong(4);
                long share = rs.getLong(5);
                return new DailySource(day(rs.getTimestamp(1)), own, composite, search, share,
                    rs.getLong(6) - own - composite - search - share);
            }, q.params().toArray());
    }

    /** 一个对象的内容每天获得的曝光和点击；没有曝光的日子不出现。 */
    public List<DailyEntry> dailyExposure(SubjectType type, long id, LocalDate from, LocalDate to) {
        Query q = query(type, SubjectFilter.ofIds(id), "impression", "i", "content_id", "impression_time", from, to);
        return jdbc.query("SELECT date_trunc('day', i.impression_time), COUNT(*),"
            + " SUM(CASE WHEN i.clicked THEN 1 ELSE 0 END) FROM " + q.from() + q.where()
            + " GROUP BY date_trunc('day', i.impression_time) ORDER BY 1",
            (rs, i) -> new DailyEntry(day(rs.getTimestamp(1)), rs.getLong(2), rs.getLong(3)), q.params().toArray());
    }

    // ---- Top 内容与内容页 ----

    /** 一个对象的 Top 内容（按期间 PV）。互动数 = 期间内这篇内容上的点赞/转发/收藏加评论。limit 是内部常量。 */
    public List<TopContent> topContents(SubjectType type, SubjectFilter filter, LocalDate from, LocalDate to, int limit) {
        Query q = query(type, filter, "pv_log", "p", "content_id", "view_time", from, to);
        // SELECT 列表里的两个子查询先出现，它们的 4 个时间参数排在 FROM/WHERE 的参数之前
        List<Object> params = new ArrayList<>(List.of(start(from), end(to), start(from), end(to)));
        params.addAll(q.params());
        return jdbc.query("SELECT c.id, c.type, a.name, c.publish_time, COUNT(p.id),"
            + " (SELECT COUNT(*) FROM engagement_log g WHERE g.content_id = c.id AND g.occur_time >= ? AND g.occur_time < ?)"
            + " + (SELECT COUNT(*) FROM comment m WHERE m.content_id = c.id AND m.create_time >= ? AND m.create_time < ?)"
            + " FROM " + q.from() + " JOIN account a ON a.id = c.account_id" + q.where()
            + " GROUP BY c.id, c.type, a.name, c.publish_time ORDER BY COUNT(p.id) DESC, c.id LIMIT " + limit,
            (rs, i) -> new TopContent(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4).toLocalDateTime(), rs.getLong(5), rs.getLong(6)),
            params.toArray());
    }

    /** 一篇内容在各入口的曝光与点击。 */
    public List<EntryExposure> entryExposure(long contentId, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT ch.id, ch.name, ch.category, COUNT(*), SUM(CASE WHEN i.clicked THEN 1 ELSE 0 END)"
            + " FROM impression i JOIN channel ch ON ch.id = i.entry_channel_id"
            + " WHERE i.content_id = ? AND i.impression_time >= ? AND i.impression_time < ?"
            + " GROUP BY ch.id, ch.name, ch.category ORDER BY COUNT(*) DESC, ch.id",
            (rs, i) -> new EntryExposure(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5)),
            contentId, start(from), end(to));
    }

    // ---- 对象信息 ----

    public Optional<AccountInfo> account(long id) {
        return jdbc.query("SELECT id, name, type FROM account WHERE id = ?",
            (rs, i) -> new AccountInfo(rs.getLong(1), rs.getString(2), rs.getString(3)), id).stream().findFirst();
    }

    public Optional<BloggerInfo> blogger(long id) {
        return jdbc.query("SELECT e.id, e.name, e.dept, e.community_join_date,"
            + " (SELECT COUNT(*) FROM blogger_rfm_score r WHERE r.employee_id = e.id) FROM employee e WHERE e.id = ?",
            (rs, i) -> {
                Timestamp joined = rs.getTimestamp(4);
                return new BloggerInfo(rs.getLong(1), rs.getString(2), rs.getString(3),
                    joined == null ? null : day(joined), rs.getLong(5) > 0);
            }, id).stream().findFirst();
    }

    public Optional<ContentInfo> content(long id) {
        return jdbc.query("SELECT c.id, c.type, c.channel_id, ch.name, c.account_id, a.name, c.publish_time"
            + " FROM content c JOIN channel ch ON ch.id = c.channel_id JOIN account a ON a.id = c.account_id"
            + " WHERE c.id = ?",
            (rs, i) -> new ContentInfo(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getLong(5),
                rs.getString(6), rs.getTimestamp(7).toLocalDateTime()), id).stream().findFirst();
    }

    public Optional<ChannelInfo> channel(long id) {
        return jdbc.query("SELECT id, name, category FROM channel WHERE id = ? AND category IN ('综合频道', '领域频道')",
            (rs, i) -> new ChannelInfo(rs.getLong(1), rs.getString(2), rs.getString(3)), id).stream().findFirst();
    }

    public List<ChannelInfo> channels(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT id, name, category FROM channel WHERE id IN ("
            + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")",
            (rs, i) -> new ChannelInfo(rs.getLong(1), rs.getString(2), rs.getString(3)), ids.toArray());
    }

    public List<AccountInfo> accounts(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT id, name, type FROM account WHERE id IN ("
            + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")",
            (rs, i) -> new AccountInfo(rs.getLong(1), rs.getString(2), rs.getString(3)), ids.toArray());
    }

    public List<PersonInfo> employees(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT id, name FROM employee WHERE id IN ("
            + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")",
            (rs, i) -> new PersonInfo(rs.getLong(1), rs.getString(2)), ids.toArray());
    }

    /** 在这个频道里发过内容的账号（不限期间）。 */
    public List<AccountInfo> accountsOfChannel(long channelId) {
        return jdbc.query("SELECT DISTINCT a.id, a.name, a.type FROM account a JOIN content c ON c.account_id = a.id"
            + " WHERE c.channel_id = ? ORDER BY a.id",
            (rs, i) -> new AccountInfo(rs.getLong(1), rs.getString(2), rs.getString(3)), channelId);
    }

    /** 在这个账号下发过内容的作者（不限期间）。 */
    public List<PersonInfo> authorsOfAccount(long accountId) {
        return jdbc.query("SELECT DISTINCT e.id, e.name FROM employee e JOIN content_author ca ON ca.employee_id = e.id"
            + " JOIN content c ON c.id = ca.content_id WHERE c.account_id = ? ORDER BY e.id",
            (rs, i) -> new PersonInfo(rs.getLong(1), rs.getString(2)), accountId);
    }

    public List<PersonInfo> authorsOfContent(long contentId) {
        return jdbc.query("SELECT e.id, e.name FROM employee e JOIN content_author ca ON ca.employee_id = e.id"
            + " WHERE ca.content_id = ? ORDER BY e.id",
            (rs, i) -> new PersonInfo(rs.getLong(1), rs.getString(2)), contentId);
    }

    public List<String> tags(long contentId) {
        return jdbc.queryForList("SELECT tag FROM content_tag WHERE content_id = ? ORDER BY id", String.class, contentId);
    }
}
