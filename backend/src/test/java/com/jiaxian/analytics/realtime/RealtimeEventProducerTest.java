package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.repository.ChannelRepository;
import com.jiaxian.analytics.repository.CommentRepository;
import com.jiaxian.analytics.repository.ContentRepository;
import com.jiaxian.analytics.repository.EmployeeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealtimeEventProducerTest {

    @Mock private KafkaTemplate<String, RealtimeEvent> kafkaTemplate;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ContentRepository contentRepository;
    @Mock private CommentRepository commentRepository;
    @Mock private ChannelRepository channelRepository;

    private RealtimeEventProducer producer;

    @BeforeEach
    void setUp() {
        producer = new RealtimeEventProducer(kafkaTemplate, employeeRepository, contentRepository,
            commentRepository, channelRepository, new RealtimeEventPlanner(new Random(1)));
    }

    @AfterEach
    void tearDown() {
        producer.stop();
    }

    private List<Employee> employees() {
        return List.of(Employee.builder().id(1L).build(), Employee.builder().id(2L).build());
    }

    private List<Content> contents() {
        return List.of(Content.builder().id(10L).popularityWeight(0.5).build());
    }

    private void stubData() {
        when(employeeRepository.findAll()).thenReturn(employees());
        when(contentRepository.findAll()).thenReturn(contents());
    }

    private void stubSend() {
        lenient().when(kafkaTemplate.send(anyString(), anyString(), any(RealtimeEvent.class)))
            .thenReturn(CompletableFuture.completedFuture(null));
    }

    private RealtimeSimulationConfig config(int eventsPerBatch) {
        return RealtimeSimulationConfig.builder()
            .batchId("producer-test").intervalMillis(20).eventsPerBatch(eventsPerBatch).build();
    }

    @Test
    void publishBatchSendsOneKeyedMessagePerPlannedEvent() {
        stubSend();

        producer.publishBatch(config(20), employees(), contents(), java.util.Map.of());

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RealtimeEvent> events = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(kafkaTemplate, times(20))
            .send(eq(RealtimeConfig.COMMUNITY_EVENTS_TOPIC), keys.capture(), events.capture());
        for (int i = 0; i < 20; i++) {
            assertThat(keys.getAllValues().get(i)).isEqualTo(events.getAllValues().get(i).partitionKey());
            assertThat(events.getAllValues().get(i).getBatchId()).isEqualTo("producer-test");
        }
        assertThat(producer.status().getSentCount()).isEqualTo(20);
    }

    @Test
    void startPublishesInBackgroundAndStopHaltsPublishing() throws Exception {
        stubData();
        stubSend();

        RealtimeStatus started = producer.start(config(5));
        assertThat(started.isRunning()).isTrue();
        assertThat(started.getBatchId()).isEqualTo("producer-test");
        assertThat(started.getStartedAt()).isNotNull();

        await().atMost(5, TimeUnit.SECONDS).until(() -> producer.status().getSentCount() > 0);

        assertThat(producer.stop()).isTrue();
        assertThat(producer.status().isRunning()).isFalse();
        long sentAtStop = producer.status().getSentCount();
        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(producer.status().getSentCount()).isEqualTo(sentAtStop);
        assertThat(producer.stop()).isFalse();
    }

    @Test
    void startingTwiceIsRejected() {
        stubData();
        stubSend();
        producer.start(config(5));

        assertThatThrownBy(() -> producer.start(config(5)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already running");
    }

    @Test
    void startFailsWithoutEmployeesOrContent() {
        when(employeeRepository.findAll()).thenReturn(List.of());
        when(contentRepository.findAll()).thenReturn(contents());

        assertThatThrownBy(() -> producer.start(config(5)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("historical backfill");
        assertThat(producer.status().isRunning()).isFalse();
    }

    @Test
    void aFailingBatchDoesNotStopTheSchedule() {
        stubData();
        lenient().when(kafkaTemplate.send(anyString(), anyString(), any(RealtimeEvent.class)))
            .thenThrow(new RuntimeException("boom"))
            .thenReturn(CompletableFuture.completedFuture(null));

        producer.start(config(1));

        await().atMost(5, TimeUnit.SECONDS).until(() -> producer.status().getSentCount() > 0);
    }
}
