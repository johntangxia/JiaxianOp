package com.jiaxian.analytics.pointsconfig;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PointsConfigControllerIT {

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private JsonNode likeItem() {
        JsonNode items = rest.getForEntity(url("/api/admin/points-config"), JsonNode.class).getBody();
        for (JsonNode item : items) {
            if (item.get("actionType").asText().equals("LIKE")) {
                return item;
            }
        }
        throw new AssertionError("LIKE not found");
    }

    @Test
    void listReturnsEveryActionTypeWithVersions() {
        ResponseEntity<JsonNode> response = rest.getForEntity(url("/api/admin/points-config"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).hasSize(15);
        JsonNode first = response.getBody().get(0);
        assertThat(first.get("category").asText()).isEqualTo("ACTIVE");
        assertThat(first.get("current").get("weight").isInt()).isTrue();
        assertThat(first.get("versions").size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void updateCreatesATodayVersionAndCanBeRolledBack() {
        String today = LocalDate.now().toString();
        Integer previousTodayWeight = null;
        for (JsonNode version : likeItem().get("versions")) {
            if (version.get("effectiveDate").asText().equals(today)) {
                previousTodayWeight = version.get("weight").asInt();
            }
        }
        long createdId = -1;
        try {
            ResponseEntity<JsonNode> response = rest.exchange(url("/api/admin/points-config/LIKE"), HttpMethod.PUT,
                json("{\"weight\":7}"), JsonNode.class);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().get("actionType").asText()).isEqualTo("LIKE");
            assertThat(response.getBody().get("current").get("weight").asInt()).isEqualTo(7);
            assertThat(response.getBody().get("current").get("effectiveDate").asText()).isEqualTo(today);
            createdId = response.getBody().get("current").get("id").asLong();
        } finally {
            if (previousTodayWeight == null && createdId > 0) {
                rest.exchange(url("/api/admin/points-config/versions/" + createdId), HttpMethod.DELETE, null, Void.class);
            } else if (previousTodayWeight != null) {
                rest.exchange(url("/api/admin/points-config/LIKE"), HttpMethod.PUT,
                    json("{\"weight\":" + previousTodayWeight + "}"), JsonNode.class);
            }
        }
    }

    @Test
    void invalidRequestsAreRejected() {
        ResponseEntity<String> unknown = rest.exchange(url("/api/admin/points-config/NO_SUCH_ACTION"), HttpMethod.PUT,
            json("{\"weight\":5}"), String.class);
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);

        ResponseEntity<String> negative = rest.exchange(url("/api/admin/points-config/LIKE"), HttpMethod.PUT,
            json("{\"weight\":-5}"), String.class);
        assertThat(negative.getStatusCode().value()).isEqualTo(400);
        assertThat(negative.getBody()).contains("weight");

        ResponseEntity<String> missing = rest.exchange(url("/api/admin/points-config/LIKE"), HttpMethod.PUT,
            json("{}"), String.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<String> notJson = rest.exchange(url("/api/admin/points-config/LIKE"), HttpMethod.PUT,
            json("{\"weight\":\"abc\"}"), String.class);
        assertThat(notJson.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<String> noVersion = rest.exchange(url("/api/admin/points-config/versions/-1"),
            HttpMethod.DELETE, null, String.class);
        assertThat(noVersion.getStatusCode().value()).isEqualTo(404);
    }
}
