package com.jiaxian.analytics.pv;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/** 每天凌晨把最近 N 天（含今天）重算一遍，把实时事件写入的 pv_log 补进汇总表。 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "analytics.pv.schedule.enabled", havingValue = "true", matchIfMissing = true)
public class PvSummaryScheduler {

    private final PvSummaryService summaryService;
    private final Clock clock;
    private final int rebuildDays;

    public PvSummaryScheduler(PvSummaryService summaryService, Clock clock,
                              @Value("${analytics.pv.rebuild-days:3}") int rebuildDays) {
        this.summaryService = summaryService;
        this.clock = clock;
        this.rebuildDays = rebuildDays;
    }

    @Scheduled(cron = "${analytics.pv.rebuild-cron:0 10 0 * * *}")
    public void rebuildRecentDays() {
        LocalDate today = LocalDate.now(clock);
        summaryService.rebuild(today.minusDays(Math.max(rebuildDays, 1) - 1L), today);
    }
}
