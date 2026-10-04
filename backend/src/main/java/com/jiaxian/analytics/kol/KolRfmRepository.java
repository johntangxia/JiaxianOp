package com.jiaxian.analytics.kol;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 每周 RFM 结果和流失预警的读写。日期列在 A 兼容模式下是 timestamp，一律绑定 Timestamp。 */
@Repository
public class KolRfmRepository {

    public record ScoreRow(BloggerMetrics metrics, RfmScores scores, Segment segment, boolean churnAlert) {
    }

    public record AlertRow(long employeeId, ChurnSignal signal) {
    }

    private static final int MAX_RUNS = 104;

    private static final int[] SCORE_TYPES = {Types.TIMESTAMP, Types.BIGINT, Types.INTEGER, Types.BIGINT,
        Types.INTEGER, Types.INTEGER, Types.BIGINT, Types.INTEGER, Types.INTEGER, Types.INTEGER,
        Types.VARCHAR, Types.BOOLEAN};

    private static final int[] ALERT_TYPES = {Types.TIMESTAMP, Types.BIGINT, Types.VARCHAR,
        Types.DOUBLE, Types.DOUBLE, Types.DOUBLE};

    private final JdbcTemplate jdbc;

    public KolRfmRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 最近一次早于 asOf 的重算里每个人的标签；没有更早的重算则为空。 */
    public Map<Long, Segment> previousSegments(LocalDate asOf) {
        Map<Long, Segment> previous = new HashMap<>();
        jdbc.query("SELECT employee_id, segment_label FROM blogger_rfm_score WHERE as_of_date ="
                + " (SELECT MAX(as_of_date) FROM blogger_rfm_score WHERE as_of_date < ?)",
            rs -> {
                previous.put(rs.getLong(1), Segment.valueOf(rs.getString(2)));
            }, ts(asOf));
        return previous;
    }

    public void deleteFor(LocalDate asOf) {
        jdbc.update("DELETE FROM churn_alert WHERE alert_date = ?", ts(asOf));
        jdbc.update("DELETE FROM blogger_rfm_score WHERE as_of_date = ?", ts(asOf));
    }

    public void insertScores(LocalDate asOf, List<ScoreRow> rows) {
        List<Object[]> batch = new ArrayList<>();
        for (ScoreRow row : rows) {
            BloggerMetrics m = row.metrics();
            batch.add(new Object[] {ts(asOf), m.employeeId(), m.rank(), m.rollingPoints12m(), m.recencyDays(),
                m.frequency(), m.monetary(), row.scores().r(), row.scores().f(), row.scores().m(),
                row.segment().name(), row.churnAlert()});
        }
        jdbc.batchUpdate("INSERT INTO blogger_rfm_score (as_of_date, employee_id, rank_no, rolling_points_12m,"
            + " recency_days, frequency, monetary, r_score, f_score, m_score, segment_label, churn_alert)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", batch, SCORE_TYPES);
    }

    public void insertAlerts(LocalDate asOf, List<AlertRow> rows) {
        List<Object[]> batch = new ArrayList<>();
        for (AlertRow row : rows) {
            ChurnSignal signal = row.signal();
            batch.add(new Object[] {ts(asOf), row.employeeId(), signal.metric().name(),
                signal.recentDaily(), signal.baselineDaily(), signal.dropRate()});
        }
        jdbc.batchUpdate("INSERT INTO churn_alert (alert_date, employee_id, metric,"
            + " recent_daily, baseline_daily, drop_rate) VALUES (?, ?, ?, ?, ?, ?)", batch, ALERT_TYPES);
    }

    public Optional<LocalDate> latestAsOf() {
        Timestamp latest = jdbc.queryForObject("SELECT MAX(as_of_date) FROM blogger_rfm_score", Timestamp.class);
        return Optional.ofNullable(latest).map(t -> t.toLocalDateTime().toLocalDate());
    }

    private static Timestamp ts(LocalDate date) {
        return Timestamp.valueOf(date.atStartOfDay());
    }

    /** 已有重算记录的统计日，新的在前，最多 MAX_RUNS 个。 */
    public List<LocalDate> runDates() {
        // LIMIT 用常量字面量（OpenGauss 不接受绑定参数形式的分页）
        return jdbc.query("SELECT DISTINCT as_of_date FROM blogger_rfm_score ORDER BY as_of_date DESC LIMIT " + MAX_RUNS,
            (rs, rowNum) -> rs.getTimestamp(1).toLocalDateTime().toLocalDate());
    }
}
