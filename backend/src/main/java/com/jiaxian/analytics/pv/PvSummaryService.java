package com.jiaxian.analytics.pv;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 pv_log 按"内容 × 天"重算进 pv_summary_daily。先删后插，同一范围重复重算结果完全一样。
 *
 * <p>OpenGauss 在 A 兼容模式下 DATE 实际是 timestamp，且 CAST(ts AS DATE) 会保留时间部分，
 * 所以这里用 date_trunc('day', ...) 截断；范围用 [from 当天零点, to 次日零点) 表达，不对列做函数运算。
 */
@Service
public class PvSummaryService {

    private final JdbcTemplate jdbc;

    public PvSummaryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** from/to 任一为 null 表示该侧不设界。返回写入的行数。 */
    @Transactional
    public int rebuild(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidPvQueryException("from must not be after to");
        }

        StringBuilder delete = new StringBuilder("DELETE FROM pv_summary_daily WHERE 1 = 1");
        StringBuilder insert = new StringBuilder(
            "INSERT INTO pv_summary_daily (content_id, summary_date, pv_count) "
                + "SELECT content_id, date_trunc('day', view_time), count(*) FROM pv_log WHERE 1 = 1");
        List<Object> deleteArgs = new ArrayList<>();
        List<Object> insertArgs = new ArrayList<>();

        if (from != null) {
            Timestamp lower = Timestamp.valueOf(from.atStartOfDay());
            delete.append(" AND summary_date >= ?");
            deleteArgs.add(lower);
            insert.append(" AND view_time >= ?");
            insertArgs.add(lower);
        }
        if (to != null) {
            Timestamp upperExclusive = Timestamp.valueOf(to.plusDays(1).atStartOfDay());
            delete.append(" AND summary_date < ?");
            deleteArgs.add(upperExclusive);
            insert.append(" AND view_time < ?");
            insertArgs.add(upperExclusive);
        }
        insert.append(" GROUP BY content_id, date_trunc('day', view_time)");

        jdbc.update(delete.toString(), deleteArgs.toArray());
        return jdbc.update(insert.toString(), insertArgs.toArray());
    }
}
