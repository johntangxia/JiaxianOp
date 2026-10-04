package com.jiaxian.analytics.pv;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PvSummarySchedulerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-04T00:10:00Z"), ZoneOffset.UTC);
    private final PvSummaryService summaryService = mock(PvSummaryService.class);

    @Test
    void rebuildsTheLastNDaysIncludingToday() {
        new PvSummaryScheduler(summaryService, clock, 3).rebuildRecentDays();

        verify(summaryService).rebuild(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 4));
    }

    @Test
    void atLeastTodayIsRebuiltEvenIfDaysIsMisconfigured() {
        new PvSummaryScheduler(summaryService, clock, 0).rebuildRecentDays();

        verify(summaryService).rebuild(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 4));
    }
}
