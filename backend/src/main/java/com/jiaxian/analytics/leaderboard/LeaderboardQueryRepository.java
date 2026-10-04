package com.jiaxian.analytics.leaderboard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/**
 * 榜单取数。实时榜对 points_ledger 的区间求和并用 RANK() 排名（并列同名次），
 * 快照榜读 leaderboard_snapshot。两条路径输出同一种 LeaderboardEntry、同样的排序。
 */
@Repository
public class LeaderboardQueryRepository {

    private static final int MAX_LIMIT = 100;

    private static final RowMapper<LeaderboardEntry> ENTRY_MAPPER = (rs, rowNum) -> new LeaderboardEntry(
        rs.getInt(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getLong(6));

    private final JdbcTemplate jdbc;

    public LeaderboardQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 区间 [start, nextStart) 内的实时榜，名次降序，并列按员工 id 升序。 */
    public List<LeaderboardEntry> live(LocalDate start, LocalDate nextStart, int limit) {
        requireSafeLimit(limit);
        String sql = "SELECT x.rank_no, x.employee_id, e.name, x.total_points, x.active_points, x.passive_points"
            + " FROM (SELECT employee_id,"
            + "              RANK() OVER (ORDER BY SUM(points) DESC) AS rank_no,"
            + "              SUM(points) AS total_points,"
            + "              SUM(CASE WHEN category = 'ACTIVE' THEN points ELSE 0 END) AS active_points,"
            + "              SUM(CASE WHEN category = 'PASSIVE' THEN points ELSE 0 END) AS passive_points"
            + "       FROM points_ledger WHERE occur_time >= ? AND occur_time < ? GROUP BY employee_id) x"
            + " JOIN employee e ON e.id = x.employee_id"
            + " ORDER BY x.rank_no, x.employee_id LIMIT " + limit;
        return jdbc.query(sql, ENTRY_MAPPER,
            Timestamp.valueOf(start.atStartOfDay()), Timestamp.valueOf(nextStart.atStartOfDay()));
    }

    public List<LeaderboardEntry> snapshot(PeriodType type, String periodValue, int limit) {
        requireSafeLimit(limit);
        String sql = "SELECT s.rank_no, s.employee_id, e.name, s.points, s.active_points, s.passive_points"
            + " FROM leaderboard_snapshot s JOIN employee e ON e.id = s.employee_id"
            + " WHERE s.period_type = ? AND s.period_value = ?"
            + " ORDER BY s.rank_no, s.employee_id LIMIT " + limit;
        return jdbc.query(sql, ENTRY_MAPPER, type.name(), periodValue);
    }

    public boolean snapshotExists(PeriodType type, String periodValue) {
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM leaderboard_snapshot WHERE period_type = ? AND period_value = ?",
            Long.class, type.name(), periodValue);
        return count != null && count > 0;
    }

    /** points_ledger 里至少有一行流水的周期起点（升序）。 */
    public List<LocalDate> periodsWithLedger(PeriodType type) {
        String unit = type == PeriodType.MONTH ? "month" : "year";
        String sql = "SELECT DISTINCT date_trunc('" + unit + "', occur_time) AS period_start"
            + " FROM points_ledger ORDER BY period_start";
        return jdbc.query(sql, (rs, rowNum) -> rs.getTimestamp(1).toLocalDateTime().toLocalDate());
    }

    private static void requireSafeLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            // LIMIT 以字面量写进 SQL（OpenGauss 不接受绑定参数形式的分页），所以必须先确认是安全的整数范围。
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
