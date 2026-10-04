package com.jiaxian.analytics.kol;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 名单查询：按统计日、分层、预警筛选，按名单名次排序并分页。LIMIT/OFFSET 以校验过的整数字面量写进 SQL。 */
@Repository
public class BloggerListRepository {

    private final JdbcTemplate jdbc;

    public BloggerListRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<BloggerRow> find(LocalDate asOf, Segment segment, Boolean alert, int limit, int offset) {
        if (limit < 1 || offset < 0) {
            throw new IllegalArgumentException("limit must be positive and offset must not be negative");
        }
        Where where = where(asOf, segment, alert);
        String sql = "SELECT r.employee_id, e.name, r.rank_no, r.rolling_points_12m, r.recency_days, r.frequency,"
            + " r.monetary, r.r_score, r.f_score, r.m_score, r.segment_label, r.churn_alert"
            + " FROM blogger_rfm_score r JOIN employee e ON e.id = r.employee_id" + where.sql()
            + " ORDER BY r.rank_no, r.employee_id LIMIT " + limit + " OFFSET " + offset;
        return jdbc.query(sql, (rs, rowNum) -> {
            int recency = rs.getInt(5);
            Integer recencyDays = rs.wasNull() ? null : recency;
            return new BloggerRow(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4), recencyDays,
                rs.getInt(6), rs.getLong(7), rs.getInt(8), rs.getInt(9), rs.getInt(10),
                Segment.valueOf(rs.getString(11)), rs.getBoolean(12));
        }, where.args().toArray());
    }

    public long count(LocalDate asOf, Segment segment, Boolean alert) {
        Where where = where(asOf, segment, alert);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM blogger_rfm_score r" + where.sql(),
            Long.class, where.args().toArray());
        return count == null ? 0 : count;
    }

    /** 该统计日未筛选的各分层人数；没有人的分层不出现（由 Service 补零）。 */
    public Map<Segment, Long> segmentCounts(LocalDate asOf) {
        Map<Segment, Long> counts = new EnumMap<>(Segment.class);
        jdbc.query("SELECT segment_label, COUNT(*) FROM blogger_rfm_score WHERE as_of_date = ? GROUP BY segment_label",
            rs -> {
                counts.put(Segment.valueOf(rs.getString(1)), rs.getLong(2));
            }, ts(asOf));
        return counts;
    }

    private static Where where(LocalDate asOf, Segment segment, Boolean alert) {
        Where where = new Where();
        where.and("r.as_of_date = ?", ts(asOf));
        if (segment != null) {
            where.and("r.segment_label = ?", segment.name());
        }
        if (alert != null) {
            where.and("r.churn_alert = ?", alert);
        }
        return where;
    }

    private static Timestamp ts(LocalDate date) {
        return Timestamp.valueOf(date.atStartOfDay());
    }

    private static final class Where {
        private final List<String> clauses = new ArrayList<>();
        private final List<Object> args = new ArrayList<>();

        void and(String clause, Object arg) {
            clauses.add(clause);
            args.add(arg);
        }

        String sql() {
            return " WHERE " + String.join(" AND ", clauses);
        }

        List<Object> args() {
            return args;
        }
    }
}
