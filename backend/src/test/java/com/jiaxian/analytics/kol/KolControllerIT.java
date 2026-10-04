package com.jiaxian.analytics.kol;

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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KolControllerIT {

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

    private HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    // ---- 名单 ----

    @Test
    void listForAnAsOfWithoutARebuildIsEmptyButStructured() {
        ResponseEntity<JsonNode> response = rest.getForEntity(
            url("/api/bloggers/top1000?asOf=2019-06-30&limit=10"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body.get("asOf").asText()).isEqualTo("2019-06-30");
        assertThat(body.get("total").asLong()).isZero();
        assertThat(body.get("items")).isEmpty();
        assertThat(body.get("segmentCounts").get("STAR_KOL").asLong()).isZero();
        assertThat(body.get("segmentCounts").size()).isEqualTo(Segment.values().length);
    }

    @Test
    void listRejectsBadParameters() {
        assertGetBadRequest("/api/bloggers/top1000?segment=VIP", "segment");
        assertGetBadRequest("/api/bloggers/top1000?alert=maybe", "alert");
        assertGetBadRequest("/api/bloggers/top1000?asOf=2019-6-30", "asOf");
        assertGetBadRequest("/api/bloggers/top1000?limit=0", "limit");
        assertGetBadRequest("/api/bloggers/top1000?limit=1001", "limit");
        assertGetBadRequest("/api/bloggers/top1000?offset=-1", "offset");
    }

    // ---- 重算 ----

    @Test
    void rebuildEndpointReturnsOneResultPerWeek() {
        ResponseEntity<JsonNode> response = rest.postForEntity(
            url("/api/admin/analytics/kol/rebuild?asOf=2019-06-30&weeks=2"), null, JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody().get(0).get("asOf").asText()).isEqualTo("2019-06-23");
        assertThat(response.getBody().get(1).get("asOf").asText()).isEqualTo("2019-06-30");
        assertThat(response.getBody().get(1).get("population").asInt()).isZero();   // 2019 年没有任何积分
        assertThat(response.getBody().get(1).get("alerts").asInt()).isZero();
    }

    @Test
    void rebuildEndpointRejectsBadRequests() {
        for (String path : new String[] {
            "/api/admin/analytics/kol/rebuild?asOf=2999-01-01", "/api/admin/analytics/kol/rebuild?weeks=0",
            "/api/admin/analytics/kol/rebuild?weeks=53", "/api/admin/analytics/kol/rebuild?asOf=2019-6-30"}) {
            ResponseEntity<String> response = rest.postForEntity(url(path), null, String.class);
            assertThat(response.getStatusCode().value()).as(path).isEqualTo(400);
        }
    }

    // ---- 文案库 ----

    @Test
    void playbookCrudRoundTrip() {
        ResponseEntity<JsonNode> list = rest.getForEntity(url("/api/admin/playbook"), JsonNode.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody().isArray()).isTrue();
        assertThat(list.getBody().size()).isGreaterThanOrEqualTo(6);

        // OBSERVE/ALERT 不在种子里，创建后用完即删
        ResponseEntity<JsonNode> created = rest.postForEntity(url("/api/admin/playbook"),
            json("{\"segmentLabel\":\"OBSERVE\",\"alertStatus\":\"ALERT\",\"recommendationText\":\"先人工看一眼\"}"),
            JsonNode.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        long id = created.getBody().get("id").asLong();
        try {
            ResponseEntity<String> duplicate = rest.postForEntity(url("/api/admin/playbook"),
                json("{\"segmentLabel\":\"OBSERVE\",\"alertStatus\":\"ALERT\",\"recommendationText\":\"重复\"}"),
                String.class);
            assertThat(duplicate.getStatusCode().value()).isEqualTo(409);

            ResponseEntity<JsonNode> updated = rest.exchange(url("/api/admin/playbook/" + id), HttpMethod.PUT,
                json("{\"segmentLabel\":\"OBSERVE\",\"alertStatus\":\"ALERT\",\"recommendationText\":\"改过的文案\"}"),
                JsonNode.class);
            assertThat(updated.getStatusCode().value()).isEqualTo(200);
            assertThat(updated.getBody().get("recommendationText").asText()).isEqualTo("改过的文案");
        } finally {
            ResponseEntity<Void> deleted = rest.exchange(url("/api/admin/playbook/" + id), HttpMethod.DELETE, null, Void.class);
            assertThat(deleted.getStatusCode().value()).isEqualTo(204);
        }
        ResponseEntity<String> deletedAgain = rest.exchange(
            url("/api/admin/playbook/" + id), HttpMethod.DELETE, null, String.class);
        assertThat(deletedAgain.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void playbookRejectsInvalidBodies() {
        ResponseEntity<String> badEnum = rest.postForEntity(url("/api/admin/playbook"),
            json("{\"segmentLabel\":\"VIP\",\"alertStatus\":\"ALERT\",\"recommendationText\":\"x\"}"), String.class);
        assertThat(badEnum.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<String> blank = rest.postForEntity(url("/api/admin/playbook"),
            json("{\"segmentLabel\":\"OBSERVE\",\"alertStatus\":\"ALERT\",\"recommendationText\":\"   \"}"), String.class);
        assertThat(blank.getStatusCode().value()).isEqualTo(400);
        assertThat(blank.getBody()).contains("recommendationText");

        ResponseEntity<String> missing = rest.exchange(url("/api/admin/playbook/-1"), HttpMethod.PUT,
            json("{\"segmentLabel\":\"OBSERVE\",\"alertStatus\":\"ALERT\",\"recommendationText\":\"x\"}"), String.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void runsEndpointReturnsAnArrayOfDates() {
        ResponseEntity<JsonNode> response = rest.getForEntity(url("/api/bloggers/runs"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().isArray()).isTrue();
        for (JsonNode date : response.getBody()) {
            assertThat(date.asText()).matches("\\d{4}-\\d{2}-\\d{2}");
        }
    }
}
