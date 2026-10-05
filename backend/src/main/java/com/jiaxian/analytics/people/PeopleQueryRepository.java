package com.jiaxian.analytics.people;

import com.jiaxian.analytics.reader.Member;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 创作者分析与用户分析独有的查询（其余复用 ReaderQueryRepository 和 SubjectQueryRepository）。
 * 区间两端含（按天），内部转成半开区间；limit 由调用方校验过（整数，直接拼进 SQL）。
 */
@Repository
public class PeopleQueryRepository {

    public record AccountTypeFigures(String type, long posts, long creators) {
    }

    public record KolEntry(LocalDate asOf, int rank, String segment) {
    }

    public record WeekActivity(LocalDate weekStart, long logins, long views, long interactions, long posts) {
    }

    public record LabelCount(String label, long count) {
    }

    public record ReadingHabits(long views, long videoViews, Double avgDwellSeconds, Double completionRate) {
    }

    public record FollowedAccount(long accountId, String accountName, LocalDateTime followTime, LocalDateTime unfollowTime) {
    }

    public record Points(long active, long passive) {
    }

    public record Event(LocalDateTime time, String type, Long refId) {
    }

    public record DeptDomainViews(String dept, String channel, long views) {
    }

    public record DeptReaders(String dept, long readers, long views) {
    }

    private static final String INTERACTION_TYPES = "('LIKE', 'COMMENT', 'SHARE', 'FAVORITE')";

    private final JdbcTemplate jdbc;

    public PeopleQueryRepository(JdbcTemplate jdbc) {
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

    private static LocalDateTime time(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    // ---- 创作者 ----

    /** 区间内每个员工每天的发帖数（POST 行为）。 */
    public List<PostDay> postDays(LocalDate from, LocalDate to) {
        return jdbc.query("SELECT employee_id, date_trunc('day', occur_time), COUNT(*) FROM active_action_log"
            + " WHERE action_type = 'POST' AND occur_time >= ? AND occur_time < ?"
            + " GROUP BY employee_id, date_trunc('day', occur_time)",
            (rs, i) -> new PostDay(rs.getLong(1), day(rs.getTimestamp(2)), rs.getLong(3)), start(from), end(to));
    }

    /** 每个发过帖的员工的首次发帖日（全部历史）。 */
    public Map<Long, LocalDate> firstPostDays() {
        Map<Long, LocalDate> result = new HashMap<>();
        jdbc.query("SELECT employee_id, MIN(occur_time) FROM active_action_log WHERE action_type = 'POST' GROUP BY employee_id",
            rs -> {
                result.put(rs.getLong(1), day(rs.getTimestamp(2)));
            });
        return result;
    }

    /**
     * 截至 asOf（含）每个有过行为的员工的累计事实：各类首次行为、最后一次行为，以及 asOf 前 90 天（含 asOf）的发帖数。
     * 没有任何行为的员工不出现。
     */
    public List<LifetimeFacts> lifetimeFacts(LocalDate asOf) {
        return jdbc.query("SELECT employee_id, MIN(occur_time),"
            + " MIN(CASE WHEN action_type = 'VIEW' THEN occur_time END),"
            + " MIN(CASE WHEN action_type IN " + INTERACTION_TYPES + " THEN occur_time END),"
            + " MIN(CASE WHEN action_type = 'POST' THEN occur_time END),"
            + " SUM(CASE WHEN action_type = 'POST' AND occur_time >= ? THEN 1 ELSE 0 END), MAX(occur_time)"
            + " FROM active_action_log WHERE occur_time < ? GROUP BY employee_id ORDER BY employee_id",
            (rs, i) -> new LifetimeFacts(rs.getLong(1), time(rs.getTimestamp(2)), time(rs.getTimestamp(3)),
                time(rs.getTimestamp(4)), time(rs.getTimestamp(5)), rs.getLong(6), time(rs.getTimestamp(7))),
            start(asOf.minusDays(89)), end(asOf));
    }

    /** 名下账号在区间内的新增关注者：员工 → 所在账号的新增关注数之和（团队账号共享时每位成员都计完整数）。 */
    public Map<Long, Long> newFollowersByEmployee(LocalDate from, LocalDate to) {
        Map<Long, Long> result = new HashMap<>();
        jdbc.query("SELECT am.employee_id, COUNT(DISTINCT f.id) FROM account_member am"
            + " JOIN follow_relation f ON f.account_id = am.account_id AND f.follow_time >= ? AND f.follow_time < ?"
            + " GROUP BY am.employee_id", rs -> {
                result.put(rs.getLong(1), rs.getLong(2));
            }, start(from), end(to));
        return result;
    }

    /** 区间内发布的内容按账号类型（PERSONAL/TEAM）拆分：内容数和去重作者数。 */
    public List<AccountTypeFigures> accountTypeSupply(LocalDate from, LocalDate to) {
        return jdbc.query("SELECT a.type, COUNT(DISTINCT c.id), COUNT(DISTINCT ca.employee_id) FROM content c"
            + " JOIN account a ON a.id = c.account_id LEFT JOIN content_author ca ON ca.content_id = c.id"
            + " WHERE c.publish_time >= ? AND c.publish_time < ? GROUP BY a.type ORDER BY a.type",
            (rs, i) -> new AccountTypeFigures(rs.getString(1), rs.getLong(2), rs.getLong(3)), start(from), end(to));
    }

    /** 账号 id → 类型。 */
    public Map<Long, String> accountTypes() {
        Map<Long, String> result = new HashMap<>();
        jdbc.query("SELECT id, type FROM account", rs -> {
            result.put(rs.getLong(1), rs.getString(2));
        });
        return result;
    }

    /** 不晚于 asOf 的最近一次 KOL 重算日期。 */
    public Optional<LocalDate> latestKolDate(LocalDate asOf) {
        Timestamp latest = jdbc.queryForObject("SELECT MAX(as_of_date) FROM blogger_rfm_score WHERE as_of_date < ?",
            Timestamp.class, end(asOf));
        return Optional.ofNullable(day(latest));
    }

    /** 最近一次 KOL 重算里被判为明星 KOL 的员工。 */
    public Set<Long> starKols(LocalDate kolDate) {
        Set<Long> result = new HashSet<>();
        jdbc.query("SELECT employee_id FROM blogger_rfm_score WHERE as_of_date = ? AND segment_label = 'STAR_KOL'", rs -> {
            result.add(rs.getLong(1));
        }, start(kolDate));
        return result;
    }

    /** 一个员工在指定 KOL 重算日的名次和分层；不在名单里为空。 */
    public Optional<KolEntry> kolEntry(long employeeId, LocalDate kolDate) {
        return jdbc.query("SELECT rank_no, segment_label FROM blogger_rfm_score WHERE as_of_date = ? AND employee_id = ?",
            rs -> rs.next() ? Optional.of(new KolEntry(kolDate, rs.getInt(1), rs.getString(2))) : Optional.<KolEntry>empty(),
            start(kolDate), employeeId);
    }

    // ---- 用户 ----

    public Optional<Member> member(long id) {
        return jdbc.query("SELECT id, name, dept, community_join_date FROM employee WHERE id = ?",
            rs -> rs.next() ? Optional.of(new Member(rs.getLong(1), rs.getString(2), rs.getString(3), day(rs.getTimestamp(4))))
                : Optional.<Member>empty(), id);
    }

    /** 一个员工区间内按周（周一起）的登录/浏览/互动/发帖次数，没有行为的周不出现。 */
    public List<WeekActivity> weeklyActivity(long employeeId, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT date_trunc('week', occur_time),"
            + " SUM(CASE WHEN action_type = 'LOGIN' THEN 1 ELSE 0 END), SUM(CASE WHEN action_type = 'VIEW' THEN 1 ELSE 0 END),"
            + " SUM(CASE WHEN action_type IN " + INTERACTION_TYPES + " THEN 1 ELSE 0 END),"
            + " SUM(CASE WHEN action_type = 'POST' THEN 1 ELSE 0 END)"
            + " FROM active_action_log WHERE employee_id = ? AND occur_time >= ? AND occur_time < ?"
            + " GROUP BY date_trunc('week', occur_time) ORDER BY 1",
            (rs, i) -> new WeekActivity(day(rs.getTimestamp(1)), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)),
            employeeId, start(from), end(to));
    }

    /** 区间内浏览最多的领域频道（按内容所属频道）。 */
    public List<LabelCount> viewedChannels(long employeeId, LocalDate from, LocalDate to, int limit) {
        return jdbc.query("SELECT ch.name, COUNT(*) FROM pv_log p JOIN content c ON c.id = p.content_id"
            + " JOIN channel ch ON ch.id = c.channel_id WHERE p.viewer_employee_id = ? AND p.view_time >= ?"
            + " AND p.view_time < ? GROUP BY ch.name ORDER BY COUNT(*) DESC, ch.name LIMIT " + limit,
            (rs, i) -> new LabelCount(rs.getString(1), rs.getLong(2)), employeeId, start(from), end(to));
    }

    /** 区间内浏览的内容里最常见的标签。 */
    public List<LabelCount> viewedTags(long employeeId, LocalDate from, LocalDate to, int limit) {
        return jdbc.query("SELECT t.tag, COUNT(*) FROM pv_log p JOIN content_tag t ON t.content_id = p.content_id"
            + " WHERE p.viewer_employee_id = ? AND p.view_time >= ? AND p.view_time < ?"
            + " GROUP BY t.tag ORDER BY COUNT(*) DESC, t.tag LIMIT " + limit,
            (rs, i) -> new LabelCount(rs.getString(1), rs.getLong(2)), employeeId, start(from), end(to));
    }

    /** 区间内浏览的来源构成：ENTRY/SEARCH/SHARE/OTHER（没有来源的旧数据记为 OTHER）。 */
    public List<LabelCount> viewSources(long employeeId, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT COALESCE(source_type, 'OTHER'), COUNT(*) FROM pv_log WHERE viewer_employee_id = ?"
            + " AND view_time >= ? AND view_time < ? GROUP BY COALESCE(source_type, 'OTHER') ORDER BY COUNT(*) DESC",
            (rs, i) -> new LabelCount(rs.getString(1), rs.getLong(2)), employeeId, start(from), end(to));
    }

    /** 区间内的浏览数、视频浏览数、平均停留和读完/播完率（只统计有停留记录的浏览）。 */
    public ReadingHabits readingHabits(long employeeId, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("SELECT COUNT(*), SUM(CASE WHEN c.type = 'VIDEO' THEN 1 ELSE 0 END),"
            + " AVG(p.dwell_seconds), AVG(CASE WHEN p.dwell_seconds IS NULL THEN NULL WHEN p.completed THEN 1.0 ELSE 0.0 END)"
            + " FROM pv_log p JOIN content c ON c.id = p.content_id WHERE p.viewer_employee_id = ?"
            + " AND p.view_time >= ? AND p.view_time < ?",
            (rs, i) -> new ReadingHabits(rs.getLong(1), rs.getLong(2), rs.getObject(3) == null ? null : rs.getDouble(3),
                rs.getObject(4) == null ? null : rs.getDouble(4)), employeeId, start(from), end(to));
    }

    /** 员工关注过的账号（含已取关），最近关注的在前。 */
    public List<FollowedAccount> follows(long employeeId) {
        return jdbc.query("SELECT a.id, a.name, f.follow_time, f.unfollow_time FROM follow_relation f"
            + " JOIN account a ON a.id = f.account_id WHERE f.follower_employee_id = ?"
            + " ORDER BY f.follow_time DESC, a.id",
            (rs, i) -> new FollowedAccount(rs.getLong(1), rs.getString(2), time(rs.getTimestamp(3)), time(rs.getTimestamp(4))),
            employeeId);
    }

    /** 区间内主动、被动积分合计。 */
    public Points points(long employeeId, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN category = 'ACTIVE' THEN points ELSE 0 END), 0),"
            + " COALESCE(SUM(CASE WHEN category = 'PASSIVE' THEN points ELSE 0 END), 0) FROM points_ledger"
            + " WHERE employee_id = ? AND occur_time >= ? AND occur_time < ?",
            (rs, i) -> new Points(rs.getLong(1), rs.getLong(2)), employeeId, start(from), end(to));
    }

    /** 不晚于 asOf 当天的最近若干条行为，新的在前。 */
    public List<Event> recentEvents(long employeeId, LocalDate asOf, int limit) {
        return jdbc.query("SELECT occur_time, action_type, ref_id FROM active_action_log WHERE employee_id = ?"
            + " AND occur_time < ? ORDER BY occur_time DESC, id DESC LIMIT " + limit,
            (rs, i) -> new Event(time(rs.getTimestamp(1)), rs.getString(2), (Long) rs.getObject(3)), employeeId, end(asOf));
    }

    // ---- 兴趣与跨领域 ----

    /** 区间内成员浏览的内容按"浏览者部门 × 内容所属频道"统计的浏览数。 */
    public List<DeptDomainViews> deptDomainViews(LocalDate from, LocalDate to) {
        return jdbc.query("SELECT e.dept, ch.name, COUNT(*) FROM pv_log p JOIN employee e ON e.id = p.viewer_employee_id"
            + " JOIN content c ON c.id = p.content_id JOIN channel ch ON ch.id = c.channel_id"
            + " WHERE e.dept IS NOT NULL AND p.view_time >= ? AND p.view_time < ? GROUP BY e.dept, ch.name",
            (rs, i) -> new DeptDomainViews(rs.getString(1), rs.getString(2), rs.getLong(3)), start(from), end(to));
    }

    /** 区间内各部门的读者人数（去重）和浏览总数。 */
    public List<DeptReaders> deptReaders(LocalDate from, LocalDate to) {
        return jdbc.query("SELECT e.dept, COUNT(DISTINCT p.viewer_employee_id), COUNT(*) FROM pv_log p"
            + " JOIN employee e ON e.id = p.viewer_employee_id WHERE e.dept IS NOT NULL AND p.view_time >= ?"
            + " AND p.view_time < ? GROUP BY e.dept ORDER BY e.dept",
            (rs, i) -> new DeptReaders(rs.getString(1), rs.getLong(2), rs.getLong(3)), start(from), end(to));
    }
}
