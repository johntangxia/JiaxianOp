package com.jiaxian.analytics.kol;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 博主详情页取数。日期列在 A 兼容模式下是 timestamp：参数一律绑定 Timestamp，读取一律走 getTimestamp；
 * 按天取数用 date_trunc('day', …) 分组，分桶补零由 Service 在 Java 里做。
 */
@Repository
public class BloggerProfileRepository {

    private static final int MAX_EVENT_LIMIT = 100;

    public record DailyActivity(LocalDate date, String type, long count) {
    }

    public record DailyPoints(LocalDate date, String category, long points) {
    }

    /** blogger_rfm_score 的一行联同一统计日的预警详情（没有预警为 null）。 */
    public record StandingRow(int rank, Segment segment, boolean churnAlert, long rollingPoints12m,
                              Integer recencyDays, int frequency, long monetary,
                              int rScore, int fScore, int mScore, AlertDetail alert) {
    }

    private final JdbcTemplate jdbc;

    public BloggerProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EmployeeInfo> employee(long id) {
        return jdbc.query("SELECT id, name, dept, join_date FROM employee WHERE id = ?", (rs, rowNum) -> {
            Timestamp joinDate = rs.getTimestamp(4);
            return new EmployeeInfo(rs.getLong(1), rs.getString(2), rs.getString(3),
                joinDate == null ? null : joinDate.toLocalDateTime().toLocalDate());
        }, id).stream().findFirst();
    }

    public Optional<StandingRow> standing(long employeeId, LocalDate asOf) {
        return jdbc.query("SELECT r.rank_no, r.segment_label, r.churn_alert, r.rolling_points_12m, r.recency_days,"
                + " r.frequency, r.monetary, r.r_score, r.f_score, r.m_score,"
                + " a.metric, a.recent_daily, a.baseline_daily, a.drop_rate"
                + " FROM blogger_rfm_score r LEFT JOIN churn_alert a"
                + " ON a.alert_date = r.as_of_date AND a.employee_id = r.employee_id"
                + " WHERE r.as_of_date = ? AND r.employee_id = ?", (rs, rowNum) -> {
            int recency = rs.getInt(5);
            Integer recencyDays = rs.wasNull() ? null : recency;
            String metric = rs.getString(11);
            AlertDetail alert = metric == null ? null : new AlertDetail(
                ChurnSignal.Metric.valueOf(metric), rs.getDouble(12), rs.getDouble(13), rs.getDouble(14));
            return new StandingRow(rs.getInt(1), Segment.valueOf(rs.getString(2)), rs.getBoolean(3), rs.getLong(4),
                recencyDays, rs.getInt(6), rs.getLong(7), rs.getInt(8), rs.getInt(9), rs.getInt(10), alert);
        }, ts(asOf), employeeId).stream().findFirst();
    }

    /** 统计日在 [from, toInclusive] 内的每周重算结果，按统计日升序。 */
    public List<HistoryPoint> history(long employeeId, LocalDate from, LocalDate toInclusive) {
        return jdbc.query("SELECT as_of_date, rank_no, segment_label, churn_alert, r_score, f_score, m_score"
                + " FROM blogger_rfm_score WHERE employee_id = ? AND as_of_date >= ? AND as_of_date <= ?"
                + " ORDER BY as_of_date", (rs, rowNum) -> {
            Segment segment = Segment.valueOf(rs.getString(3));
            return new HistoryPoint(rs.getTimestamp(1).toLocalDateTime().toLocalDate(), rs.getInt(2), segment,
                segment.displayName(), rs.getBoolean(4), rs.getInt(5), rs.getInt(6), rs.getInt(7));
        }, employeeId, ts(from), ts(toInclusive));
    }

    /** 范围内每天每种类型（发帖/评论/登录）的次数，from、toInclusive 都包含。 */
    public List<DailyActivity> dailyActivity(long employeeId, LocalDate from, LocalDate toInclusive) {
        return jdbc.query("SELECT date_trunc('day', occur_time), action_type, COUNT(*)"
                + " FROM active_action_log WHERE employee_id = ? AND action_type IN ('POST', 'COMMENT', 'LOGIN')"
                + " AND occur_time >= ? AND occur_time < ?"
                + " GROUP BY date_trunc('day', occur_time), action_type",
            (rs, rowNum) -> new DailyActivity(rs.getTimestamp(1).toLocalDateTime().toLocalDate(),
                rs.getString(2), rs.getLong(3)),
            employeeId, ts(from), ts(toInclusive.plusDays(1)));
    }

    /** 范围内每天主动/被动积分之和，from、toInclusive 都包含。 */
    public List<DailyPoints> dailyPoints(long employeeId, LocalDate from, LocalDate toInclusive) {
        return jdbc.query("SELECT date_trunc('day', occur_time), category, SUM(points)"
                + " FROM points_ledger WHERE employee_id = ? AND occur_time >= ? AND occur_time < ?"
                + " GROUP BY date_trunc('day', occur_time), category",
            (rs, rowNum) -> new DailyPoints(rs.getTimestamp(1).toLocalDateTime().toLocalDate(),
                rs.getString(2), rs.getLong(3)),
            employeeId, ts(from), ts(toInclusive.plusDays(1)));
    }

    /** 最近的发帖/评论/登录，时间倒序（同一时间按 id 倒序）。 */
    public List<RecentEvent> recentEvents(long employeeId, int limit) {
        if (limit < 1 || limit > MAX_EVENT_LIMIT) {
            // LIMIT 以字面量写进 SQL（OpenGauss 不接受绑定参数形式的分页），所以必须先确认是安全的整数范围。
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_EVENT_LIMIT);
        }
        return jdbc.query("SELECT occur_time, action_type, ref_id FROM active_action_log"
                + " WHERE employee_id = ? AND action_type IN ('POST', 'COMMENT', 'LOGIN')"
                + " ORDER BY occur_time DESC, id DESC LIMIT " + limit, (rs, rowNum) -> {
            // wasNull() 反映最后读取的那一列，所以读完 ref_id 要立刻判断，不能放到别的列之后
            long refId = rs.getLong(3);
            Long nullableRefId = rs.wasNull() ? null : refId;
            return new RecentEvent(rs.getTimestamp(1).toLocalDateTime(), rs.getString(2), nullableRefId);
        }, employeeId);
    }

    private static Timestamp ts(LocalDate date) {
        return Timestamp.valueOf(date.atStartOfDay());
    }
}
