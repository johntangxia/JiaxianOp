package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.repository.ContentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealtimeCounterUpdaterTest {

    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 4, 12, 0);
    private static final String PV_KEY = "rt:pv:channel:7:2026-10-04";
    private static final String ENGAGEMENT_KEY = "rt:engagement:channel:7:2026-10-04";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private ContentRepository contentRepository;

    private RealtimeCounterUpdater updater;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        updater = new RealtimeCounterUpdater(redisTemplate, contentRepository);
    }

    private void contentInChannel7() {
        when(contentRepository.findById(10L))
            .thenReturn(Optional.of(Content.builder().id(10L).channelId(7L).build()));
    }

    private RealtimeEvent event(RealtimeEventType type, Long contentId) {
        return RealtimeEvent.builder()
            .eventType(type).contentId(contentId).actorEmployeeId(1L).occurTime(TIME).batchId("b").build();
    }

    @Test
    void viewIncrementsChannelPvCounterOnly() {
        contentInChannel7();
        when(valueOperations.increment(PV_KEY)).thenReturn(5L);

        updater.record(event(RealtimeEventType.VIEW, 10L));

        verify(valueOperations).increment(PV_KEY);
        verify(valueOperations, never()).increment(ENGAGEMENT_KEY);
        verify(redisTemplate, never()).expireAt(anyString(), any(Instant.class));
    }

    @ParameterizedTest
    @EnumSource(value = RealtimeEventType.class,
        names = {"LIKE", "SHARE", "FAVORITE", "COMMENT", "COMMENT_LIKE"})
    void engagementEventsIncrementChannelEngagementCounterOnly(RealtimeEventType type) {
        contentInChannel7();
        when(valueOperations.increment(ENGAGEMENT_KEY)).thenReturn(3L);

        updater.record(event(type, 10L));

        verify(valueOperations).increment(ENGAGEMENT_KEY);
        verify(valueOperations, never()).increment(PV_KEY);
    }

    @Test
    void firstIncrementOfAKeySetsExpiryAtNextMidnight() {
        contentInChannel7();
        when(valueOperations.increment(PV_KEY)).thenReturn(1L);

        updater.record(event(RealtimeEventType.VIEW, 10L));

        ArgumentCaptor<Instant> expiry = ArgumentCaptor.forClass(Instant.class);
        verify(redisTemplate).expireAt(eq(PV_KEY), expiry.capture());
        assertThat(expiry.getValue())
            .isEqualTo(LocalDate.of(2026, 10, 5).atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    @Test
    void loginDoesNotTouchRedisOrTheContentRepository() {
        updater.record(event(RealtimeEventType.LOGIN, null));

        verifyNoInteractions(valueOperations, contentRepository);
    }

    @Test
    void eventWithoutContentIdIsIgnored() {
        updater.record(event(RealtimeEventType.COMMENT_LIKE, null));

        verifyNoInteractions(valueOperations, contentRepository);
    }

    @Test
    void unknownContentIsIgnored() {
        when(contentRepository.findById(10L)).thenReturn(Optional.empty());

        updater.record(event(RealtimeEventType.VIEW, 10L));

        verifyNoInteractions(valueOperations);
    }
}
