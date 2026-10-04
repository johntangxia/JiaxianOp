package com.jiaxian.analytics.dimension;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * 前端筛选和选择器用的维度数据。账号/员工按名称模糊搜索：不区分大小写，关键字里的 % 和 _ 按字面匹配
 * （用 '!' 作转义字符）；LIMIT 以校验过的整数字面量写进 SQL（OpenGauss 不接受绑定参数形式的分页）。
 */
@Repository
public class DimensionRepository {

    private static final int MAX_LIMIT = 100;

    private final JdbcTemplate jdbc;

    public DimensionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ChannelOption> channels() {
        return jdbc.query("SELECT id, name, category FROM channel ORDER BY id",
            (rs, rowNum) -> new ChannelOption(rs.getLong(1), rs.getString(2), rs.getString(3)));
    }

    public List<AccountOption> accounts(String keyword, int limit) {
        requireSafeLimit(limit);
        return jdbc.query("SELECT id, name, type FROM account WHERE LOWER(name) LIKE ? ESCAPE '!'"
                + " ORDER BY name, id LIMIT " + limit,
            (rs, rowNum) -> new AccountOption(rs.getLong(1), rs.getString(2), rs.getString(3)),
            likePattern(keyword));
    }

    public List<EmployeeOption> employees(String keyword, int limit) {
        requireSafeLimit(limit);
        return jdbc.query("SELECT id, name, dept FROM employee WHERE LOWER(name) LIKE ? ESCAPE '!'"
                + " ORDER BY name, id LIMIT " + limit,
            (rs, rowNum) -> new EmployeeOption(rs.getLong(1), rs.getString(2), rs.getString(3)),
            likePattern(keyword));
    }

    /** 汇总表里最早和最晚的日期（A 兼容模式下 DATE 实际是 timestamp，所以走 getTimestamp）。 */
    public PvRange pvRange() {
        return jdbc.queryForObject("SELECT MIN(summary_date), MAX(summary_date) FROM pv_summary_daily",
            (rs, rowNum) -> new PvRange(toDate(rs.getTimestamp(1)), toDate(rs.getTimestamp(2))));
    }

    static String likePattern(String keyword) {
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        String escaped = normalized.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    private static LocalDate toDate(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toLocalDate();
    }

    private static void requireSafeLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
