package com.jiaxian.analytics.reader;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 读者侧的聚合查询，全部基于 active_action_log（员工的主动行为流水）。区间两端含（按天），内部转成半开区间。
 * 互动 = 点赞、评论、转发、收藏。
 */
@Repository
public class ReaderQueryRepository {

    /** 一个员工在周期内的各类行为计数；lastActive 是周期内最后一次行为的日期。 */
    public record MemberActivity(long employeeId, long posts, long interactions, long views, long events,
                                 long activeDays, LocalDate lastActive) {
    }

    public record Lurker(long employeeId, String name, String dept, LocalDate joinDate, long views, long activeDays,
                         LocalDate lastActive) {
    }

    public record DateSpan(LocalDate from, LocalDate to) {
    }

    public record LurkerPage(long total, List<Lurker> lurkers) {
    }

    private static final String INTERACTION_TYPES = "('LIKE', 'COMMENT', 'SHARE', 'FAVORITE')";

    private final JdbcTemplate jdbc;

    public ReaderQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Timestamp start(LocalDate from) {
        return Timestamp.valueOf(from.atStartOfDay());
    }

    private static Timestamp end(LocalDate to) {
        return Timestamp.valueOf(to.plusDays(1).atStartOfDay());
    }

    private static LocalDate day(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toLocalDate();
    }

    public List<Member> members() {
        return jdbc.query("SELECT id, name, dept, community_join_date FROM employee ORDER BY id",
            (rs, i) -> new Member(rs.getLong(1), rs.getString(2), rs.getString(3), day(rs.getTimestamp(4))));
    }

    /** 区间内每个员工每天一行（去重）。 */
    public List<ActiveDay> activeDays(LocalDate from, LocalDate to) {
        return jdbc.query("SELECT employee_id, date_trunc('day', occur_time) FROM active_action_log"
            + " WHERE occur_time >= ? AND occur_time < ? GROUP BY employee_id, date_trunc('day', occur_time)",
            (rs, i) -> new ActiveDay(rs.getLong(1), day(rs.getTimestamp(2))), start(from), end(to));
    }

    /** 最近一次有行为记录的日期；没有任何记录时为空。留存同期群用它判断某个周期有没有被数据完整覆盖。 */
    public Optional<LocalDate> latestActiveDay() {
        Timestamp latest = jdbc.queryForObject("SELECT MAX(occur_time) FROM active_action_log", Timestamp.class);
        return Optional.ofNullable(day(latest));
    }

    /** 区间内有过行为的员工的各类行为计数。 */
    public List<MemberActivity> memberActivity(LocalDate from, LocalDate to) {
        return jdbc.query("SELECT employee_id,"
            + " SUM(CASE WHEN action_type = 'POST' THEN 1 ELSE 0 END),"
            + " SUM(CASE WHEN action_type IN " + INTERACTION_TYPES + " THEN 1 ELSE 0 END),"
            + " SUM(CASE WHEN action_type = 'VIEW' THEN 1 ELSE 0 END), COUNT(*),"
            + " COUNT(DISTINCT date_trunc('day', occur_time)), MAX(occur_time)"
            + " FROM active_action_log WHERE occur_time >= ? AND occur_time < ? GROUP BY employee_id",
            (rs, i) -> new MemberActivity(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                rs.getLong(6), day(rs.getTimestamp(7))), start(from), end(to));
    }

    /** 加入日期在区间内的成员的首次行为：浏览、互动、发帖，以及加入后第 2 周（第 7–14 天）是否仍活跃。 */
    public List<FunnelFacts> funnelFacts(LocalDate from, LocalDate to) {
        return jdbc.query("SELECT e.id, e.community_join_date,"
            + " MIN(CASE WHEN a.action_type = 'VIEW' THEN a.occur_time END),"
            + " MIN(CASE WHEN a.action_type IN " + INTERACTION_TYPES + " THEN a.occur_time END),"
            + " MIN(CASE WHEN a.action_type = 'POST' THEN a.occur_time END),"
            + " MAX(CASE WHEN a.occur_time >= e.community_join_date + INTERVAL '7 days'"
            + "           AND a.occur_time < e.community_join_date + INTERVAL '14 days' THEN 1 ELSE 0 END)"
            + " FROM employee e LEFT JOIN active_action_log a ON a.employee_id = e.id"
            + " WHERE e.community_join_date >= ? AND e.community_join_date < ?"
            + " GROUP BY e.id, e.community_join_date ORDER BY e.id",
            (rs, i) -> new FunnelFacts(rs.getLong(1), day(rs.getTimestamp(2)), time(rs.getTimestamp(3)),
                time(rs.getTimestamp(4)), time(rs.getTimestamp(5)), rs.getInt(6) == 1), start(from), end(to));
    }

    private static LocalDateTime time(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    /**
     * 沉默读者：区间内有浏览，但没发过帖也没互动过，且区间结束日已是成员。按浏览次数从高到低、同数按员工 id。
     * dept 为空表示不按部门筛选；limit、offset 由调用方校验过（整数，直接拼进 SQL）。
     */
    public LurkerPage lurkers(LocalDate from, LocalDate to, String dept, int limit, int offset) {
        List<Object> params = new ArrayList<>(List.of(start(from), end(to), end(to)));
        String deptFilter = "";
        if (dept != null) {
            deptFilter = " AND e.dept = ?";
            params.add(dept);
        }
        String core = " FROM employee e JOIN active_action_log a ON a.employee_id = e.id"
            + " AND a.occur_time >= ? AND a.occur_time < ?"
            + " WHERE (e.community_join_date IS NULL OR e.community_join_date < ?)" + deptFilter
            + " GROUP BY e.id, e.name, e.dept, e.community_join_date"
            + " HAVING SUM(CASE WHEN a.action_type = 'VIEW' THEN 1 ELSE 0 END) > 0"
            + " AND SUM(CASE WHEN a.action_type = 'POST' THEN 1 ELSE 0 END) = 0"
            + " AND SUM(CASE WHEN a.action_type IN " + INTERACTION_TYPES + " THEN 1 ELSE 0 END) = 0";

        long total = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT e.id" + core + ") t", Long.class, params.toArray());
        List<Lurker> lurkers = jdbc.query("SELECT e.id, e.name, e.dept, e.community_join_date,"
            + " SUM(CASE WHEN a.action_type = 'VIEW' THEN 1 ELSE 0 END), COUNT(DISTINCT date_trunc('day', a.occur_time)),"
            + " MAX(a.occur_time)" + core
            + " ORDER BY SUM(CASE WHEN a.action_type = 'VIEW' THEN 1 ELSE 0 END) DESC, e.id LIMIT " + limit
            + " OFFSET " + offset,
            (rs, i) -> new Lurker(rs.getLong(1), rs.getString(2), rs.getString(3), day(rs.getTimestamp(4)), rs.getLong(5),
                rs.getLong(6), day(rs.getTimestamp(7))), params.toArray());
        return new LurkerPage(total, lurkers);
    }

    /** 登录/发帖行为的日期范围；没有记录时为空。模拟数据里它的终点就是模拟的"现在"（浏览会延续到历史结束日之后，不能算）。 */
    public Optional<DateSpan> loginPostSpan() {
        return jdbc.query("SELECT MIN(occur_time), MAX(occur_time) FROM active_action_log WHERE action_type IN ('LOGIN', 'POST')",
            rs -> {
                Timestamp min = rs.next() ? rs.getTimestamp(1) : null;
                return min == null ? Optional.<DateSpan>empty() : Optional.of(new DateSpan(day(min), day(rs.getTimestamp(2))));
            });
    }

    /** 成员的部门列表（去重、排序），给沉默读者名单的部门筛选用。 */
    public List<String> depts() {
        return jdbc.queryForList("SELECT DISTINCT dept FROM employee WHERE dept IS NOT NULL ORDER BY dept", String.class);
    }
}
