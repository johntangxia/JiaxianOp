package com.jiaxian.analytics.realtime;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealtimeSimulatorControllerIT {

    @Autowired private TestRestTemplate restTemplate;
    @LocalServerPort private int port;
    @MockBean private RealtimeEventProducer producer;

    private String url(String path) {
        return "http://localhost:" + port + "/api/admin/simulator/realtime" + path;
    }

    private ResponseEntity<String> postJson(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(url(path), new HttpEntity<>(body, headers), String.class);
    }

    private RealtimeStatus runningStatus(String batchId) {
        return new RealtimeStatus(true, batchId, 0, LocalDateTime.of(2026, 10, 4, 12, 0));
    }

    @Test
    void startPassesTheRequestedParametersToTheProducer() {
        when(producer.start(any())).thenReturn(runningStatus("ctrl-batch"));

        ResponseEntity<String> response = postJson("/start",
            "{\"batchId\":\"ctrl-batch\",\"intervalMillis\":500,\"eventsPerBatch\":7}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"running\":true").contains("\"batchId\":\"ctrl-batch\"");
        ArgumentCaptor<RealtimeSimulationConfig> config = ArgumentCaptor.forClass(RealtimeSimulationConfig.class);
        verify(producer).start(config.capture());
        assertThat(config.getValue().getBatchId()).isEqualTo("ctrl-batch");
        assertThat(config.getValue().getIntervalMillis()).isEqualTo(500);
        assertThat(config.getValue().getEventsPerBatch()).isEqualTo(7);
    }

    @Test
    void startWithEmptyBodyUsesDefaults() {
        when(producer.start(any())).thenReturn(runningStatus("generated"));

        ResponseEntity<String> response = postJson("/start", "{}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<RealtimeSimulationConfig> config = ArgumentCaptor.forClass(RealtimeSimulationConfig.class);
        verify(producer).start(config.capture());
        assertThat(config.getValue().getBatchId()).startsWith("realtime-");
        assertThat(config.getValue().getIntervalMillis()).isEqualTo(2000);
        assertThat(config.getValue().getEventsPerBatch()).isEqualTo(10);
    }

    @Test
    void startRejectsNonPositiveParameters() {
        ResponseEntity<String> response = postJson("/start", "{\"intervalMillis\":0,\"eventsPerBatch\":5}");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(producer, never()).start(any());
    }

    @Test
    void startWhileRunningReturnsConflict() {
        when(producer.start(any())).thenThrow(new IllegalStateException("Realtime simulator is already running"));

        ResponseEntity<String> response = postJson("/start", "{}");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("already running");
    }

    @Test
    void stopReturnsTheFinalStatusWhenItWasRunning() {
        when(producer.stop()).thenReturn(true);
        when(producer.status()).thenReturn(new RealtimeStatus(false, "ctrl-batch", 42, null));

        ResponseEntity<String> response = postJson("/stop", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"running\":false").contains("\"sentCount\":42");
    }

    @Test
    void stopWhenNotRunningReturnsAClearMessageInsteadOfAnError() {
        when(producer.stop()).thenReturn(false);

        ResponseEntity<String> response = postJson("/stop", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("not running");
    }

    @Test
    void statusReturnsTheProducerSnapshot() {
        when(producer.status()).thenReturn(runningStatus("ctrl-batch"));

        ResponseEntity<String> response = restTemplate.getForEntity(url("/status"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"running\":true").contains("\"batchId\":\"ctrl-batch\"");
    }
}
