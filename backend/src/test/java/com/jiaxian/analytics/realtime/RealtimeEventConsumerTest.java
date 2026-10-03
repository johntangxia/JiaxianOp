package com.jiaxian.analytics.realtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RealtimeEventConsumerTest {

    @Mock private RealtimeEventHandler eventHandler;
    @Mock private RealtimeCounterUpdater counterUpdater;

    private RealtimeEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new RealtimeEventConsumer(eventHandler, counterUpdater);
    }

    private RealtimeEvent event(String batchId) {
        return RealtimeEvent.builder()
            .eventType(RealtimeEventType.VIEW).contentId(10L).actorEmployeeId(1L)
            .occurTime(LocalDateTime.of(2026, 10, 4, 12, 0)).batchId(batchId).build();
    }

    @Test
    void successfulEventIsPersistedBeforeCountersAreUpdated() {
        RealtimeEvent event = event("b1");

        consumer.onEvent(event);

        InOrder order = inOrder(eventHandler, counterUpdater);
        order.verify(eventHandler).handle(event);
        order.verify(counterUpdater).record(event);
        assertThat(consumer.getProcessedCount("b1")).isEqualTo(1);
    }

    @Test
    void handlerFailureIsSwallowedAndSkipsTheCounters() {
        RealtimeEvent event = event("b1");
        doThrow(new IllegalArgumentException("bad event")).when(eventHandler).handle(event);

        assertThatCode(() -> consumer.onEvent(event)).doesNotThrowAnyException();

        verifyNoInteractions(counterUpdater);
        assertThat(consumer.getProcessedCount("b1")).isEqualTo(1);
    }

    @Test
    void counterFailureIsSwallowed() {
        RealtimeEvent event = event("b1");
        doThrow(new IllegalStateException("redis down")).when(counterUpdater).record(event);

        assertThatCode(() -> consumer.onEvent(event)).doesNotThrowAnyException();

        assertThat(consumer.getProcessedCount("b1")).isEqualTo(1);
    }

    @Test
    void processedCountsAreTrackedPerBatch() {
        consumer.onEvent(event("a"));
        consumer.onEvent(event("a"));
        consumer.onEvent(event("b"));

        assertThat(consumer.getProcessedCount("a")).isEqualTo(2);
        assertThat(consumer.getProcessedCount("b")).isEqualTo(1);
        assertThat(consumer.getProcessedCount("never-seen")).isZero();
    }
}
