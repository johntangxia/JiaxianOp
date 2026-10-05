package com.jiaxian.analytics.contest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Optional;

@Repository
public class ContestRepository {

    public record DataRange(LocalDate from, LocalDate to) {
    }

    private final JdbcTemplate jdbc;

    public ContestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 浏览明细的日期范围；没有任何浏览时为空。 */
    public Optional<DataRange> pvDataRange() {
        return jdbc.query("SELECT MIN(view_time), MAX(view_time) FROM pv_log", rs -> {
            Timestamp min = rs.next() ? rs.getTimestamp(1) : null;
            if (min == null) {
                return Optional.<DataRange>empty();
            }
            return Optional.of(new DataRange(min.toLocalDateTime().toLocalDate(),
                rs.getTimestamp(2).toLocalDateTime().toLocalDate()));
        });
    }
}
