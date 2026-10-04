package com.jiaxian.analytics.leaderboard;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LeaderboardSnapshotSchedulerTest {

    private final LeaderboardSnapshotService snapshotService = mock(LeaderboardSnapshotService.class);

    private LeaderboardSnapshotScheduler schedulerAt(String instant) {
        return new LeaderboardSnapshotScheduler(snapshotService,
            Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    void rebuildsThePreviousMonthAndThePreviousYear() {
        schedulerAt("2026-10-04T00:20:00Z").rebuildPreviousPeriods();

        verify(snapshotService).rebuild(PeriodType.MONTH, "2026-09");
        verify(snapshotService).rebuild(PeriodType.YEAR, "2025");
    }

    @Test
    void onNewYearsDayThePreviousMonthIsDecember() {
        schedulerAt("2026-01-01T00:20:00Z").rebuildPreviousPeriods();

        verify(snapshotService).rebuild(PeriodType.MONTH, "2025-12");
        verify(snapshotService).rebuild(PeriodType.YEAR, "2025");
    }
}
