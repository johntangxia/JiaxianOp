package com.jiaxian.analytics.simulator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimulatorControllerIT {

    @Autowired private TestRestTemplate restTemplate;
    @LocalServerPort private int port;

    @Test
    void triggersBackfillAndReturnsAccepted() {
        String requestBody = """
            {
              "batchId": "api-test-batch",
              "employeeCount": 10,
              "accountCount": 5,
              "teamAccountRatio": 0.2,
              "contentCount": 15,
              "historyStart": "2026-01-01",
              "historyEnd": "2026-02-01",
              "randomSeed": 5
            }
            """;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
            "http://localhost:" + port + "/api/admin/simulator/backfill", request, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("api-test-batch");
    }
}
