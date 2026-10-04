package com.jiaxian.analytics.leaderboard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;

/** 把已结束周期的榜单落进 leaderboard_snapshot。先删后插，同一周期重复重算结果完全一样。 */
@Service
public class LeaderboardSnapshotService {

    private final JdbcTemplate jdbc;
    private final LeaderboardQueryRepository repository;
    private final Clock clock;

    public LeaderboardSnapshotService(JdbcTemplate jdbc, LeaderboardQueryRepository repository, Clock clock) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.clock = clock;
    }

    /** 重算一期快照，返回写入行数。周期必须已经结束。 */
    @Transactional
    public int rebuild(PeriodType type, String periodValue) {
        LocalDate start = type.parse(periodValue);
        LocalDate nextStart = type.nextStart(start);
        if (nextStart.isAfter(LocalDate.now(clock))) {
            throw new InvalidLeaderboardQueryException("Period " + periodValue + " has not ended yet");
        }
        // 用解析后重新格式化的规范值写进 SQL 字面量（SELECT ? 这种裸参数 OpenGauss 推断不出类型）。
        String canonical = type.format(start);

        jdbc.update("DELETE FROM leaderboard_snapshot WHERE period_type = ? AND period_value = ?",
            type.name(), canonical);
        String insert = "INSERT INTO leaderboard_snapshot"
            + " (period_type, period_value, rank_no, employee_id, points, active_points, passive_points)"
            + " SELECT '" + type.name() + "', '" + canonical + "',"
            + "        RANK() OVER (ORDER BY SUM(points) DESC), employee_id, SUM(points),"
            + "        SUM(CASE WHEN category = 'ACTIVE' THEN points ELSE 0 END),"
            + "        SUM(CASE WHEN category = 'PASSIVE' THEN points ELSE 0 END)"
            + " FROM points_ledger WHERE occur_time >= ? AND occur_time < ? GROUP BY employee_id";
        return jdbc.update(insert,
            Timestamp.valueOf(start.atStartOfDay()), Timestamp.valueOf(nextStart.atStartOfDay()));
    }

    /**
     * 对所有"已结束且 points_ledger 里有流水"的月和年重算。放在同一个事务里
     * （rebuild 是自调用，事务靠这里的注解提供）。
     */
    @Transactional
    public SnapshotSummary rebuildAllEnded() {
        LocalDate today = LocalDate.now(clock);
        int months = 0;
        int years = 0;
        int rows = 0;
        for (PeriodType type : PeriodType.values()) {
            for (LocalDate start : repository.periodsWithLedger(type)) {
                if (type.nextStart(start).isAfter(today)) {
                    continue;
                }
                rows += rebuild(type, type.format(start));
                if (type == PeriodType.MONTH) {
                    months++;
                } else {
                    years++;
                }
            }
        }
        return new SnapshotSummary(months, years, rows);
    }
}
