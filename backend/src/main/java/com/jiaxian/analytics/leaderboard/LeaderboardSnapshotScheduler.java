package com.jiaxian.analytics.leaderboard;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/** 每天凌晨重算"上一个月"和"上一年"的快照。幂等，所以漏跑一次也会在第二天自愈。 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "analytics.leaderboard.schedule.enabled", havingValue = "true", matchIfMissing = true)
public class LeaderboardSnapshotScheduler {

    private final LeaderboardSnapshotService snapshotService;
    private final Clock clock;

    public LeaderboardSnapshotScheduler(LeaderboardSnapshotService snapshotService, Clock clock) {
        this.snapshotService = snapshotService;
        this.clock = clock;
    }

    @Scheduled(cron = "${analytics.leaderboard.snapshot-cron:0 20 0 * * *}")
    public void rebuildPreviousPeriods() {
        LocalDate today = LocalDate.now(clock);
        LocalDate previousMonth = PeriodType.MONTH.containing(today).minusMonths(1);
        LocalDate previousYear = PeriodType.YEAR.containing(today).minusYears(1);
        snapshotService.rebuild(PeriodType.MONTH, PeriodType.MONTH.format(previousMonth));
        snapshotService.rebuild(PeriodType.YEAR, PeriodType.YEAR.format(previousYear));
    }
}
