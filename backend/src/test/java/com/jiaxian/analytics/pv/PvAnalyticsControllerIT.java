package com.jiaxian.analytics.pv;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PvAnalyticsControllerIT {

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void assertBadRequest(String path, String expectedMessagePart) {
        ResponseEntity<String> response = rest.getForEntity(url(path), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains(expectedMessagePart);
    }

    @Test
    void trendReturnsStructuredSeriesForAnUnknownChannel() {
        ResponseEntity<JsonNode> response = rest.getForEntity(
            url("/api/analytics/pv/trend?granularity=DAY&from=2019-06-01&to=2019-06-03&channelId=-1&forecastPeriods=0"),
            JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body.get("granularity").asText()).isEqualTo("DAY");
        assertThat(body.get("from").asText()).isEqualTo("2019-06-01");
        assertThat(body.get("to").asText()).isEqualTo("2019-06-03");
        assertThat(body.get("points")).hasSize(3);
        assertThat(body.get("points").get(0).get("periodStart").asText()).isEqualTo("2019-06-01");
        assertThat(body.get("points").get(0).get("pv").asLong()).isZero();
        assertThat(body.get("points").get(0).get("complete").asBoolean()).isTrue();
        assertThat(body.get("forecast")).isEmpty();
    }

    @Test
    void topReturnsEmptyArrayForAnUnknownChannelFilter() {
        ResponseEntity<JsonNode> response = rest.getForEntity(
            url("/api/analytics/pv/top?dimension=ACCOUNT&from=2019-06-01&to=2019-06-30&channelId=-1"),
            JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().isArray()).isTrue();
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void trendRejectsBadParameters() {
        assertBadRequest("/api/analytics/pv/trend?granularity=HOURLY&from=2019-06-01&to=2019-06-03", "granularity");
        assertBadRequest("/api/analytics/pv/trend?granularity=DAY&from=06/01/2019&to=2019-06-03", "from");
        assertBadRequest("/api/analytics/pv/trend?granularity=DAY&from=2019-06-05&to=2019-06-03", "from must not be after to");
        assertBadRequest("/api/analytics/pv/trend?granularity=DAY&from=2019-06-01&to=2019-06-03&forecastPeriods=13", "forecastPeriods");
        assertBadRequest("/api/analytics/pv/trend?granularity=DAY&from=2019-06-01&to=2019-06-03&contentType=BLOG", "contentType");
        assertBadRequest("/api/analytics/pv/trend?granularity=DAY&from=2019-06-01", "to");
    }

    @Test
    void topRejectsBadParameters() {
        assertBadRequest("/api/analytics/pv/top?dimension=TEAM&from=2019-06-01&to=2019-06-30", "dimension");
        assertBadRequest("/api/analytics/pv/top?dimension=CHANNEL&from=2019-06-01&to=2019-06-30&limit=0", "limit");
        assertBadRequest("/api/analytics/pv/top?dimension=CHANNEL&from=2019-06-01&to=2019-06-30&limit=101", "limit");
        assertBadRequest("/api/analytics/pv/top?dimension=CHANNEL&from=2019-06-30&to=2019-06-01", "from must not be after to");
    }

    @Test
    void rebuildEndpointReturnsRowsWritten() {
        ResponseEntity<JsonNode> response = rest.postForEntity(
            url("/api/admin/analytics/pv/rebuild?from=2019-06-01&to=2019-06-02"), null, JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("from").asText()).isEqualTo("2019-06-01");
        assertThat(response.getBody().get("to").asText()).isEqualTo("2019-06-02");
        assertThat(response.getBody().get("rowsWritten").asInt()).isZero();   // 2019 年没有任何浏览记录
    }

    @Test
    void rebuildEndpointRejectsReversedRange() {
        ResponseEntity<String> response = rest.postForEntity(
            url("/api/admin/analytics/pv/rebuild?from=2019-06-05&to=2019-06-01"), null, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("from must not be after to");
    }
}
