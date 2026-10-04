package com.jiaxian.analytics.kol;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/** 每周一凌晨以"今天"为统计日重算 KOL 名单与分层。 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "analytics.kol.schedule.enabled", havingValue = "true", matchIfMissing = true)
public class KolScheduler {

    private final KolRebuildService rebuildService;
    private final Clock clock;

    public KolScheduler(KolRebuildService rebuildService, Clock clock) {
        this.rebuildService = rebuildService;
        this.clock = clock;
    }

    @Scheduled(cron = "${analytics.kol.rebuild-cron:0 0 1 * * MON}")
    public void rebuildWeekly() {
        rebuildService.rebuild(LocalDate.now(clock));
    }
}
