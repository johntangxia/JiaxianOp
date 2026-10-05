package com.jiaxian.analytics.growth;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GrowthAlertSchedulerTest {

    private final GrowthService service = mock(GrowthService.class);
    private final GrowthAlertScheduler scheduler = new GrowthAlertScheduler(service);

    @Test
    void rebuildsAlertsWithTheDefaultAsOfDay() {
        scheduler.rebuildDaily();

        verify(service, times(1)).rebuildAlerts(null);
    }

    @Test
    void aFailedRebuildIsLoggedAndDoesNotKillTheSchedule() {
        when(service.rebuildAlerts(null)).thenThrow(new IllegalStateException("db down"));

        assertThatCode(scheduler::rebuildDaily).doesNotThrowAnyException();
    }

    @Test
    void runsDailyAtSevenInTheMorningByDefault() throws Exception {
        Scheduled scheduled = GrowthAlertScheduler.class.getMethod("rebuildDaily").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${analytics.growth.alert-cron:0 0 7 * * *}");
    }
}
