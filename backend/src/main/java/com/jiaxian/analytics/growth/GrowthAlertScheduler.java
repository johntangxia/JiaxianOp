package com.jiaxian.analytics.growth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每天早上按规则重算一遍告警（统计日取最近一次登录/发帖的日期）。重算是幂等的：已有告警保留状态，已关闭的不会重开，
 * 所以多跑一次没有副作用；失败只记日志，等下一次。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "analytics.growth.schedule.enabled", havingValue = "true", matchIfMissing = true)
public class GrowthAlertScheduler {

    private final GrowthService growthService;

    public GrowthAlertScheduler(GrowthService growthService) {
        this.growthService = growthService;
    }

    @Scheduled(cron = "${analytics.growth.alert-cron:0 0 7 * * *}")
    public void rebuildDaily() {
        try {
            AlertRebuildResult result = growthService.rebuildAlerts(null);
            log.info("Daily growth alert rebuild: {}", result);
        } catch (Exception e) {
            log.error("Daily growth alert rebuild failed, will retry tomorrow", e);
        }
    }
}
