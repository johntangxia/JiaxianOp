package com.jiaxian.analytics.leaderboard;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** 校验参数并选择数据来源：进行中实时算；已结束读快照；已结束但没有快照回退实时算。 */
@Service
public class LeaderboardService {

    static final int MAX_LIMIT = 100;

    private final LeaderboardQueryRepository repository;
    private final Clock clock;

    public LeaderboardService(LeaderboardQueryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** period 为 null 表示当前周期。 */
    public Leaderboard leaderboard(PeriodType type, String period, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidLeaderboardQueryException("limit must be between 1 and " + MAX_LIMIT);
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate start = period == null ? type.containing(today) : type.parse(period);
        if (start.isAfter(today)) {
            throw new InvalidLeaderboardQueryException("period must not be in the future");
        }
        String value = type.format(start);
        LocalDate nextStart = type.nextStart(start);

        if (nextStart.isAfter(today)) {
            return new Leaderboard(type, value, Leaderboard.Status.IN_PROGRESS, Leaderboard.Source.LIVE,
                repository.live(start, nextStart, limit));
        }
        if (repository.snapshotExists(type, value)) {
            return new Leaderboard(type, value, Leaderboard.Status.ARCHIVED, Leaderboard.Source.SNAPSHOT,
                repository.snapshot(type, value, limit));
        }
        List<LeaderboardEntry> entries = repository.live(start, nextStart, limit);
        return new Leaderboard(type, value, Leaderboard.Status.ARCHIVED, Leaderboard.Source.LIVE, entries);
    }
}
