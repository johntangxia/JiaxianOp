package com.jiaxian.analytics.contest;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** 只检查接口形状和参数校验（2019 年没有数据，榜单分数全是 0）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContestControllerIT {

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void boardsReturnTwentyThreeDomainAndFiveCompositeEntries() {
        ResponseEntity<JsonNode> response = rest.getForEntity(
            url("/api/contest/boards?type=WEEK&periodStart=2019-06-03"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body.get("type").asText()).isEqualTo("WEEK");
        assertThat(body.get("periodEnd").asText()).isEqualTo("2019-06-09");
        assertThat(body.get("previousStart").asText()).isEqualTo("2019-05-27");
        assertThat(body.get("previousHasData").asBoolean()).isFalse();
        assertThat(body.get("domain")).hasSize(23);
        assertThat(body.get("composite")).hasSize(5);
        assertThat(body.get("improvers").isArray()).isTrue();
        JsonNode first = body.get("domain").get(0);
        assertThat(first.get("dimensions")).hasSize(5);
        assertThat(first.has("rank")).isTrue();
        assertThat(first.get("rankChange").isNull()).isTrue();
        assertThat(body.get("composite").get(0).get("dimensions")).hasSize(5);
    }

    @Test
    void periodsReturnsAnArray() {
        ResponseEntity<JsonNode> response = rest.getForEntity(url("/api/contest/periods?type=MONTH"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().isArray()).isTrue();
    }

    @Test
    void invalidRequestsGetA400WithAReason() {
        for (String path : new String[] {
            "/api/contest/boards?type=WEEK&periodStart=2019-06-04",          // 不是周一
            "/api/contest/boards?type=MONTH&periodStart=2019-06-02",         // 不是 1 号
            "/api/contest/boards?type=YEAR&periodStart=2019-01-01",
            "/api/contest/boards?type=WEEK",
            "/api/contest/boards?type=WEEK&periodStart=2019-6-3",
            "/api/contest/periods"}) {
            ResponseEntity<String> response = rest.getForEntity(url(path), String.class);
            assertThat(response.getStatusCode().value()).as(path).isEqualTo(400);
            assertThat(response.getBody()).as(path).isNotBlank();
        }
    }
}
