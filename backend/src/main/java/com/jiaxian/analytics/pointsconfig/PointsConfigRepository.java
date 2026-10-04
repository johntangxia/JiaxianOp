package com.jiaxian.analytics.pointsconfig;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** points_weight_config 的读写。生效日期列在 A 兼容模式下是 timestamp：写入绑定 Timestamp，读取走 getTimestamp。 */
@Repository
public class PointsConfigRepository {

    public record Row(long id, String actionType, String category, int weight, LocalDate effectiveDate) {
    }

    private static final String SELECT =
        "SELECT id, action_type, category, weight, effective_date FROM points_weight_config";

    private static final RowMapper<Row> MAPPER = (rs, rowNum) -> new Row(rs.getLong(1), rs.getString(2),
        rs.getString(3), rs.getInt(4), rs.getTimestamp(5).toLocalDateTime().toLocalDate());

    private final JdbcTemplate jdbc;

    public PointsConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 全部版本，按动作类型分组，组内生效日期新的在前。 */
    public List<Row> all() {
        return jdbc.query(SELECT + " ORDER BY action_type, effective_date DESC, id DESC", MAPPER);
    }

    public Optional<Row> find(long id) {
        return jdbc.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<Row> findByDate(String actionType, LocalDate effectiveDate) {
        return jdbc.query(SELECT + " WHERE action_type = ? AND effective_date = ?", MAPPER,
            actionType, ts(effectiveDate)).stream().findFirst();
    }

    public Optional<String> categoryOf(String actionType) {
        return jdbc.query("SELECT category FROM points_weight_config WHERE action_type = ? LIMIT 1",
            (rs, rowNum) -> rs.getString(1), actionType).stream().findFirst();
    }

    public void insert(String actionType, String category, int weight, LocalDate effectiveDate) {
        jdbc.update("INSERT INTO points_weight_config (action_type, category, weight, effective_date)"
            + " VALUES (?, ?, ?, ?)", actionType, category, weight, ts(effectiveDate));
    }

    public void updateWeight(long id, int weight) {
        jdbc.update("UPDATE points_weight_config SET weight = ? WHERE id = ?", weight, id);
    }

    public long countVersions(String actionType) {
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM points_weight_config WHERE action_type = ?", Long.class, actionType);
        return count == null ? 0 : count;
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM points_weight_config WHERE id = ?", id);
    }

    private static Timestamp ts(LocalDate date) {
        return Timestamp.valueOf(date.atStartOfDay());
    }
}
