package com.jiaxian.analytics.kol;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 取统计日 asOf 的名单（近 12 个月积分前 N）和每人的各窗口指标。所有窗口按天对齐，右端是 asOf 次日零点（开区间）：
 * <ul>
 *   <li>近 90 天：[asOf − (window-days − 1), asOf]</li>
 *   <li>近期：[asOf − (recent-days − 1), asOf]，起点记为 recentStart</li>
 *   <li>基线：[recentStart − baseline-days, recentStart)</li>
 *   <li>上一个近期：[recentStart − recent-days, recentStart)</li>
 * </ul>
 */
@Repository
public class KolMetricsRepository {

    private record ActivityRow(Timestamp last, long window, long recent, long baseline) {
    }

    private record PointsRow(long window, long recent, long baseline, long previousRecent) {
    }

    private final JdbcTemplate jdbc;

    public KolMetricsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 最近一次登录或发帖发生的日期；没有任何记录时为空。模拟数据里它就是模拟的"现在"。 */
    public java.util.Optional<LocalDate> latestActivityDay() {
        Timestamp latest = jdbc.queryForObject(
            "SELECT MAX(occur_time) FROM active_action_log WHERE action_type IN ('LOGIN', 'POST')", Timestamp.class);
        return java.util.Optional.ofNullable(latest).map(t -> t.toLocalDateTime().toLocalDate());
    }

    public List<BloggerMetrics> loadPopulation(LocalDate asOf, KolProperties props) {
        if (props.getTopN() < 1) {
            // LIMIT 以字面量写进 SQL，必须确认是安全的正整数。
            throw new IllegalArgumentException("top-n must be positive");
        }
        LocalDate recentStartDate = asOf.minusDays(props.getRecentDays() - 1L);
        Timestamp end = ts(asOf.plusDays(1));
        Timestamp rollingStart = ts(asOf.plusDays(1).minusYears(1));
        Timestamp windowStart = ts(asOf.minusDays(props.getWindowDays() - 1L));
        Timestamp recentStart = ts(recentStartDate);
        Timestamp baselineStart = ts(recentStartDate.minusDays(props.getBaselineDays()));
        Timestamp previousRecentStart = ts(recentStartDate.minusDays(props.getRecentDays()));

        // 1. 名单：近 12 个月积分前 N，积分相同按员工 id 升序
        Map<Long, Long> rolling = new LinkedHashMap<>();
        jdbc.query("SELECT employee_id, SUM(points) FROM points_ledger"
                + " WHERE occur_time >= ? AND occur_time < ? GROUP BY employee_id"
                + " ORDER BY SUM(points) DESC, employee_id ASC LIMIT " + props.getTopN(),
            rs -> {
                rolling.put(rs.getLong(1), rs.getLong(2));
            }, rollingStart, end);
        if (rolling.isEmpty()) {
            return List.of();
        }

        // 2. 活跃行为（发帖/评论/登录）：最近一次时间 + 三个窗口内的次数
        Map<Long, ActivityRow> activity = new HashMap<>();
        jdbc.query("SELECT employee_id, MAX(occur_time),"
                + " SUM(CASE WHEN occur_time >= ? THEN 1 ELSE 0 END),"
                + " SUM(CASE WHEN occur_time >= ? THEN 1 ELSE 0 END),"
                + " SUM(CASE WHEN occur_time >= ? AND occur_time < ? THEN 1 ELSE 0 END)"
                + " FROM active_action_log WHERE action_type IN ('POST', 'COMMENT', 'LOGIN') AND occur_time < ?"
                + " GROUP BY employee_id",
            rs -> {
                activity.put(rs.getLong(1),
                    new ActivityRow(rs.getTimestamp(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)));
            }, windowStart, recentStart, baselineStart, recentStart, end);

        // 3. 积分：近 90 天、近期、基线、上一个近期
        Timestamp earliest = min(windowStart, min(baselineStart, previousRecentStart));
        Map<Long, PointsRow> points = new HashMap<>();
        jdbc.query("SELECT employee_id,"
                + " SUM(CASE WHEN occur_time >= ? THEN points ELSE 0 END),"
                + " SUM(CASE WHEN occur_time >= ? THEN points ELSE 0 END),"
                + " SUM(CASE WHEN occur_time >= ? AND occur_time < ? THEN points ELSE 0 END),"
                + " SUM(CASE WHEN occur_time >= ? AND occur_time < ? THEN points ELSE 0 END)"
                + " FROM points_ledger WHERE occur_time >= ? AND occur_time < ? GROUP BY employee_id",
            rs -> {
                points.put(rs.getLong(1),
                    new PointsRow(rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)));
            }, windowStart, recentStart, baselineStart, recentStart, previousRecentStart, recentStart, earliest, end);

        List<BloggerMetrics> population = new ArrayList<>();
        int rank = 0;
        for (Map.Entry<Long, Long> entry : rolling.entrySet()) {
            long employeeId = entry.getKey();
            ActivityRow a = activity.get(employeeId);
            PointsRow p = points.get(employeeId);
            Integer recencyDays = a == null || a.last() == null ? null
                : (int) ChronoUnit.DAYS.between(a.last().toLocalDateTime().toLocalDate(), asOf);
            population.add(new BloggerMetrics(employeeId, ++rank, entry.getValue(), recencyDays,
                a == null ? 0 : (int) a.window(), p == null ? 0 : p.window(),
                a == null ? 0 : a.recent(), a == null ? 0 : a.baseline(),
                p == null ? 0 : p.recent(), p == null ? 0 : p.baseline(), p == null ? 0 : p.previousRecent()));
        }
        return population;
    }

    private static Timestamp ts(LocalDate date) {
        return Timestamp.valueOf(date.atStartOfDay());
    }

    private static Timestamp min(Timestamp a, Timestamp b) {
        return a.before(b) ? a : b;
    }
}
