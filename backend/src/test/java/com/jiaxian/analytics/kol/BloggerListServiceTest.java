package com.jiaxian.analytics.kol;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BloggerListServiceTest {

    private final BloggerListRepository listRepository = mock(BloggerListRepository.class);
    private final KolRfmRepository rfmRepository = mock(KolRfmRepository.class);
    private final PlaybookService playbookService = mock(PlaybookService.class);
    private final BloggerListService service = new BloggerListService(listRepository, rfmRepository, playbookService);

    @Test
    void withoutAnyRebuildTheListIsEmptyAndAsOfIsNull() {
        when(rfmRepository.latestAsOf()).thenReturn(Optional.empty());

        BloggerList list = service.list(null, null, null, 50, 0);

        assertThat(list.asOf()).isNull();
        assertThat(list.total()).isZero();
        assertThat(list.items()).isEmpty();
        assertThat(list.segmentCounts()).hasSize(Segment.values().length).allSatisfy((s, c) -> assertThat(c).isZero());
        verify(listRepository, never()).find(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void defaultsToTheLatestRebuild() {
        LocalDate latest = LocalDate.of(2026, 3, 1);
        when(rfmRepository.latestAsOf()).thenReturn(Optional.of(latest));
        when(playbookService.lookup()).thenReturn(new PlaybookLookup(List.of()));
        when(listRepository.count(latest, null, null)).thenReturn(0L);
        when(listRepository.find(eq(latest), eq(null), eq(null), eq(50), eq(0))).thenReturn(List.of());
        when(listRepository.segmentCounts(latest)).thenReturn(Map.of());

        BloggerList list = service.list(null, null, null, 50, 0);

        assertThat(list.asOf()).isEqualTo(latest);
        assertThat(list.segmentCounts()).hasSize(Segment.values().length);
    }
}
