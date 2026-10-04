package com.jiaxian.analytics.kol;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KolSchedulerTest {

    @Test
    void rebuildsTodayEveryWeek() {
        KolRebuildService service = mock(KolRebuildService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-05T01:00:00Z"), ZoneOffset.UTC);

        new KolScheduler(service, clock).rebuildWeekly();

        verify(service).rebuild(LocalDate.of(2026, 10, 5));
    }
}
