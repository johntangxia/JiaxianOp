package com.jiaxian.analytics.leaderboard;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaderboardControllerIT {

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void assertGetBadRequest(String path, String expectedMessagePart) {
        ResponseEntity<String> response = rest.getForEntity(url(path), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains(expectedMessagePart);
    }

    @Test
    void archivedPeriodWithoutDataReturnsAnEmptyBoard() {
        ResponseEntity<JsonNode> response = rest.getForEntity(
            url("/api/leaderboard/month?period=2019-03&limit=10"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body.get("periodType").asText()).isEqualTo("MONTH");
        assertThat(body.get("periodValue").asText()).isEqualTo("2019-03");
        assertThat(body.get("status").asText()).isEqualTo("ARCHIVED");
        assertThat(body.get("source").asText()).isEqualTo("LIVE");   // 2019-03 没有快照，回退实时
        assertThat(body.get("entries").isArray()).isTrue();
        assertThat(body.get("entries")).isEmpty();
    }

    @Test
    void defaultsToTheCurrentPeriodInProgress() {
        ResponseEntity<JsonNode> response = rest.getForEntity(url("/api/leaderboard/year"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("periodValue").asText()).isEqualTo(String.valueOf(LocalDate.now().getYear()));
        assertThat(response.getBody().get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(response.getBody().get("source").asText()).isEqualTo("LIVE");
    }

    @Test
    void leaderboardRejectsBadParameters() {
        assertGetBadRequest("/api/leaderboard/week", "month");
        assertGetBadRequest("/api/leaderboard/MONTH", "month");
        assertGetBadRequest("/api/leaderboard/month?period=2019-3", "yyyy-MM");
        assertGetBadRequest("/api/leaderboard/year?period=2999", "future");
        assertGetBadRequest("/api/leaderboard/month?limit=0", "limit");
        assertGetBadRequest("/api/leaderboard/month?limit=101", "limit");
        assertGetBadRequest("/api/leaderboard/month?limit=abc", "limit");
    }

    @Test
    void snapshotEndpointRebuildsOnePeriod() {
        ResponseEntity<JsonNode> response = rest.postForEntity(
            url("/api/admin/analytics/leaderboard/snapshot?periodType=MONTH&periodValue=2019-03"), null, JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("periodType").asText()).isEqualTo("MONTH");
        assertThat(response.getBody().get("periodValue").asText()).isEqualTo("2019-03");
        assertThat(response.getBody().get("rowsWritten").asInt()).isZero();   // 2019 年没有任何流水
    }

    @Test
    void snapshotEndpointWithoutParametersRebuildsEverything() {
        ResponseEntity<JsonNode> response = rest.postForEntity(
            url("/api/admin/analytics/leaderboard/snapshot"), null, JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().has("months")).isTrue();
        assertThat(response.getBody().has("years")).isTrue();
        assertThat(response.getBody().has("rowsWritten")).isTrue();
    }

    @Test
    void snapshotEndpointRejectsBadRequests() {
        String base = "/api/admin/analytics/leaderboard/snapshot";
        String currentMonth = PeriodType.MONTH.format(PeriodType.MONTH.containing(LocalDate.now()));

        assertPostBadRequest(base + "?periodType=MONTH", "together");
        assertPostBadRequest(base + "?periodValue=2019-03", "together");
        assertPostBadRequest(base + "?periodType=MONTH&periodValue=" + currentMonth, "not ended");
        assertPostBadRequest(base + "?periodType=DAY&periodValue=2019-03", "periodType");
    }

    private void assertPostBadRequest(String path, String expectedMessagePart) {
        ResponseEntity<String> response = rest.postForEntity(url(path), null, String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains(expectedMessagePart);
    }

    @Test
    void periodsEndpointStartsWithTheCurrentPeriodInProgress() {
        ResponseEntity<JsonNode> response = rest.getForEntity(url("/api/leaderboard/month/periods"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().isArray()).isTrue();
        JsonNode first = response.getBody().get(0);
        assertThat(first.get("periodValue").asText())
            .isEqualTo(PeriodType.MONTH.format(PeriodType.MONTH.containing(LocalDate.now())));
        assertThat(first.get("status").asText()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void periodsEndpointRejectsAnUnknownType() {
        assertGetBadRequest("/api/leaderboard/week/periods", "month");
    }
}
