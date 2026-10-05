package com.jiaxian.analytics.kol;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KolRebuildDefaultAsOfTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-05T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private final KolMetricsRepository metrics = mock(KolMetricsRepository.class);
    private final KolRebuildService service =
        new KolRebuildService(metrics, mock(KolRfmRepository.class), new KolProperties(), clock);

    @Test
    void usesTheLatestLoginOrPostDay() {
        when(metrics.latestActivityDay()).thenReturn(Optional.of(LocalDate.of(2026, 9, 4)));

        assertThat(service.defaultAsOf()).isEqualTo(LocalDate.of(2026, 9, 4));
    }

    @Test
    void neverGoesPastToday() {
        when(metrics.latestActivityDay()).thenReturn(Optional.of(TODAY.plusDays(3)));

        assertThat(service.defaultAsOf()).isEqualTo(TODAY);
    }

    @Test
    void fallsBackToTodayWithoutAnyActivity() {
        when(metrics.latestActivityDay()).thenReturn(Optional.empty());

        assertThat(service.defaultAsOf()).isEqualTo(TODAY);
    }
}
