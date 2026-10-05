package com.jiaxian.analytics.growth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 增长闭环的查询：告警和任务的分页列表、流失预警博主、内容所属频道。limit/offset 由调用方校验过（整数，直接拼进 SQL）。 */
@Repository
public class GrowthQueryRepository {

    public record AlertPage(long total, List<GrowthAlert> alerts) {
    }

    public record TaskPage(long total, List<GrowthTask> tasks) {
    }

    private final JdbcTemplate jdbc;

    public GrowthQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static LocalDate date(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toLocalDate();
    }

    private static Long nullableLong(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static final String ALERT_COLUMNS =
        "id, kind, severity, entity_type, entity_id, entity_name, period_end, message, status, created_at, updated_at";

    private static final RowMapper<GrowthAlert> ALERT_MAPPER = (rs, i) -> GrowthAlert.builder()
        .id(rs.getLong(1)).kind(rs.getString(2)).severity(rs.getString(3))
        .entityType(GrowthEntityType.valueOf(rs.getString(4))).entityId(nullableLong(rs, 5)).entityName(rs.getString(6))
        .periodEnd(date(rs.getTimestamp(7))).message(rs.getString(8)).status(AlertStatus.valueOf(rs.getString(9)))
        .createdAt(rs.getTimestamp(10).toLocalDateTime()).updatedAt(rs.getTimestamp(11).toLocalDateTime()).build();

    /** 告警分页：严重的在前，再按创建时间倒序。任何筛选条件为空表示不筛选。 */
    public AlertPage alerts(AlertStatus status, String kind, GrowthEntityType entityType, int limit, int offset) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (status != null) {
            where.append(" AND status = ?");
            params.add(status.name());
        }
        if (kind != null) {
            where.append(" AND kind = ?");
            params.add(kind);
        }
        if (entityType != null) {
            where.append(" AND entity_type = ?");
            params.add(entityType.name());
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM growth_alert" + where, Long.class, params.toArray());
        List<GrowthAlert> alerts = jdbc.query("SELECT " + ALERT_COLUMNS + " FROM growth_alert" + where
            + " ORDER BY severity ASC, created_at DESC, id DESC LIMIT " + limit + " OFFSET " + offset, ALERT_MAPPER, params.toArray());
        return new AlertPage(total, alerts);
    }

    /** 各状态的告警数（没有的状态为 0）。 */
    public Map<AlertStatus, Long> alertCounts() {
        Map<AlertStatus, Long> counts = new HashMap<>();
        for (AlertStatus status : AlertStatus.values()) {
            counts.put(status, 0L);
        }
        jdbc.query("SELECT status, COUNT(*) FROM growth_alert GROUP BY status",
            rs -> {
                counts.put(AlertStatus.valueOf(rs.getString(1)), rs.getLong(2));
            });
        return counts;
    }

    private static final String TASK_COLUMNS = "id, title, task_type, entity_type, entity_id, entity_name, metric, baseline_value,"
        + " target_value, owner_name, start_date, due_date, status, note, alert_id, created_at, updated_at";

    private static final RowMapper<GrowthTask> TASK_MAPPER = (rs, i) -> GrowthTask.builder()
        .id(rs.getLong(1)).title(rs.getString(2)).taskType(TaskType.valueOf(rs.getString(3)))
        .entityType(GrowthEntityType.valueOf(rs.getString(4))).entityId(nullableLong(rs, 5)).entityName(rs.getString(6))
        .metric(rs.getString(7)).baselineValue(nullableDouble(rs, 8)).targetValue(rs.getDouble(9)).ownerName(rs.getString(10))
        .startDate(date(rs.getTimestamp(11))).dueDate(date(rs.getTimestamp(12))).status(TaskStatus.valueOf(rs.getString(13)))
        .note(rs.getString(14)).alertId(nullableLong(rs, 15)).createdAt(rs.getTimestamp(16).toLocalDateTime())
        .updatedAt(rs.getTimestamp(17).toLocalDateTime()).build();

    /** 任务分页，最新的在前；status 为空表示不筛选。 */
    public TaskPage tasks(TaskStatus status, int limit, int offset) {
        String where = status == null ? "" : " WHERE status = ?";
        Object[] params = status == null ? new Object[0] : new Object[] {status.name()};
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM growth_task" + where, Long.class, params);
        List<GrowthTask> tasks = jdbc.query("SELECT " + TASK_COLUMNS + " FROM growth_task" + where
            + " ORDER BY id DESC LIMIT " + limit + " OFFSET " + offset, TASK_MAPPER, params);
        return new TaskPage(total, tasks);
    }

    /** 最近一次不晚于 asOf 的 KOL 重算里被判为流失预警的博主，按名次从前到后。 */
    public List<ChurnRisk> churnRisks(LocalDate asOf, int limit) {
        return jdbc.query("SELECT r.employee_id, e.name, r.rank_no FROM blogger_rfm_score r JOIN employee e ON e.id = r.employee_id"
            + " WHERE r.as_of_date = (SELECT MAX(as_of_date) FROM blogger_rfm_score WHERE as_of_date <= ?)"
            + " AND r.segment_label = 'CHURN_RISK' ORDER BY r.rank_no LIMIT " + limit,
            (rs, i) -> new ChurnRisk(rs.getLong(1), rs.getString(2), rs.getInt(3)), Timestamp.valueOf(asOf.atStartOfDay()));
    }

    /** 每篇内容所属的频道（内容 id → 频道 id）。 */
    public Map<Long, Long> contentChannels() {
        Map<Long, Long> channels = new HashMap<>();
        jdbc.query("SELECT id, channel_id FROM content", rs -> {
            channels.put(rs.getLong(1), rs.getLong(2));
        });
        return channels;
    }
}
