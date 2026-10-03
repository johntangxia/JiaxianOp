package com.jiaxian.analytics.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.JacksonUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RealtimeEventTest {

    // 和 Spring Kafka 的 JsonSerializer/JsonDeserializer 默认使用的是同一个 ObjectMapper 工厂
    private final ObjectMapper objectMapper = JacksonUtils.enhancedObjectMapper();

    @Test
    void jsonRoundTripPreservesAllFields() throws Exception {
        RealtimeEvent original = RealtimeEvent.builder()
            .eventType(RealtimeEventType.COMMENT)
            .contentId(10L)
            .parentCommentId(20L)
            .targetCommentId(30L)
            .actorEmployeeId(40L)
            .occurTime(LocalDateTime.of(2026, 10, 4, 12, 30, 15, 123_000_000))
            .batchId("batch-x")
            .build();

        String json = objectMapper.writeValueAsString(original);
        RealtimeEvent restored = objectMapper.readValue(json, RealtimeEvent.class);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void partitionKeyIsNotSerializedIntoTheMessage() throws Exception {
        RealtimeEvent event = RealtimeEvent.builder()
            .eventType(RealtimeEventType.VIEW).contentId(1L).actorEmployeeId(2L).build();

        assertThat(objectMapper.writeValueAsString(event)).doesNotContain("partitionKey");
    }

    @Test
    void contentEventsArePartitionedByContentId() {
        RealtimeEvent event = RealtimeEvent.builder()
            .eventType(RealtimeEventType.LIKE).contentId(42L).actorEmployeeId(7L).build();

        assertThat(event.partitionKey()).isEqualTo("42");
    }

    @Test
    void loginEventsArePartitionedByActorEmployeeId() {
        RealtimeEvent event = RealtimeEvent.builder()
            .eventType(RealtimeEventType.LOGIN).actorEmployeeId(7L).build();

        assertThat(event.partitionKey()).isEqualTo("7");
    }

    @Test
    void unknownEventTypeFailsDeserialization() {
        String json = "{\"eventType\":\"SOMETHING_ELSE\",\"actorEmployeeId\":1}";

        assertThatThrownBy(() -> objectMapper.readValue(json, RealtimeEvent.class))
            .isInstanceOf(JsonProcessingException.class);
    }
}
