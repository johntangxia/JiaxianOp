package com.jiaxian.analytics.pv;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 pv_summary_daily 取数。频道/账号/博主/内容类型都在查询时通过 JOIN content（和 content_author）得到。
 *
 * <p>日期列在 A 兼容模式下是 timestamp：参数一律绑定 Timestamp，读取一律走 getTimestamp。
 * 一篇内容有多个共同作者时，每位作者都记完整 PV；content_author 可能有重复行，所以
 * 过滤用 EXISTS、Top N 用 SELECT DISTINCT 子查询，避免重复计数。
 */
@Repository
public class PvDailyQueryRepository {

    private static final int MAX_LIMIT = 100;

    private static final String FROM_CONTENT =
        " FROM pv_summary_daily s JOIN content c ON c.id = s.content_id";

    private final JdbcTemplate jdbc;

    public PvDailyQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 范围内每一天的 PV（只返回有数据的日子，补零由 PvSeriesBuilder 负责）。from、to 都包含。 */
    public List<DailyPv> dailyPv(LocalDate from, LocalDate to, PvFilter filter) {
        Where where = where(from, to, filter);
        String sql = "SELECT s.summary_date, SUM(s.pv_count)" + FROM_CONTENT + where.sql()
            + " GROUP BY s.summary_date ORDER BY s.summary_date";
        return jdbc.query(sql,
            (rs, rowNum) -> new DailyPv(rs.getTimestamp(1).toLocalDateTime().toLocalDate(), rs.getLong(2)),
            where.args().toArray());
    }

    /** 按维度汇总 PV 的前 limit 名：PV 降序，PV 相同按 id（内容类型按名字）升序。 */
    public List<PvTopEntry> top(PvDimension dimension, LocalDate from, LocalDate to, PvFilter filter, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            // LIMIT 以字面量写进 SQL（OpenGauss 不接受绑定参数形式的分页），所以必须先确认是安全的整数范围。
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        Where where = where(from, to, filter);
        String sql = switch (dimension) {
            case CHANNEL -> topSql("ch.id", "ch.name",
                " JOIN channel ch ON ch.id = c.channel_id", "ch.id, ch.name", "ch.id", where, limit);
            case ACCOUNT -> topSql("a.id", "a.name",
                " JOIN account a ON a.id = c.account_id", "a.id, a.name", "a.id", where, limit);
            case EMPLOYEE -> topSql("e.id", "e.name",
                " JOIN (SELECT DISTINCT content_id, employee_id FROM content_author) ca ON ca.content_id = c.id"
                    + " JOIN employee e ON e.id = ca.employee_id", "e.id, e.name", "e.id", where, limit);
            case CONTENT_TYPE -> topSql("CAST(NULL AS BIGINT)", "c.type",
                "", "c.type", "c.type", where, limit);
        };
        return jdbc.query(sql, (rs, rowNum) -> {
            long id = rs.getLong(1);
            Long nullableId = rs.wasNull() ? null : id;
            return new PvTopEntry(nullableId, rs.getString(2), rs.getLong(3));
        }, where.args().toArray());
    }

    private static String topSql(String idExpr, String nameExpr, String extraJoins,
                                 String groupBy, String tieBreaker, Where where, int limit) {
        return "SELECT " + idExpr + ", " + nameExpr + ", SUM(s.pv_count)" + FROM_CONTENT + extraJoins
            + where.sql() + " GROUP BY " + groupBy
            + " ORDER BY SUM(s.pv_count) DESC, " + tieBreaker + " ASC LIMIT " + limit;
    }

    private static Where where(LocalDate from, LocalDate to, PvFilter filter) {
        Where where = new Where();
        where.and("s.summary_date >= ?", Timestamp.valueOf(from.atStartOfDay()));
        where.and("s.summary_date < ?", Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        if (filter.channelId() != null) {
            where.and("c.channel_id = ?", filter.channelId());
        }
        if (filter.accountId() != null) {
            where.and("c.account_id = ?", filter.accountId());
        }
        if (filter.contentType() != null) {
            where.and("c.type = ?", filter.contentType().name());
        }
        if (filter.employeeId() != null) {
            where.and("EXISTS (SELECT 1 FROM content_author fa"
                + " WHERE fa.content_id = c.id AND fa.employee_id = ?)", filter.employeeId());
        }
        return where;
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
