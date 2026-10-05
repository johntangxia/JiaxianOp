package com.jiaxian.analytics.channel;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** 只检查接口形状和参数校验（2019 年没有数据，指标全是空值）。数据口径由仓库和服务的测试负责。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChannelCockpitControllerIT {

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @LocalServerPort private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> get(String path) {
        return rest.getForEntity(url(path), String.class);
    }

    @Test
    void cockpitReturnsFiveCompositeAndTwentyThreeDomainRows() {
        ResponseEntity<JsonNode> response = rest.getForEntity(
            url("/api/channels/cockpit?from=2019-06-01&to=2019-06-30"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body.get("from").asText()).isEqualTo("2019-06-01");
        assertThat(body.get("previousTo").asText()).isEqualTo("2019-05-31");
        assertThat(body.get("previousFrom").asText()).isEqualTo("2019-05-02");
        assertThat(body.get("composite")).hasSize(5);
        assertThat(body.get("domain")).hasSize(23);
        JsonNode domain = body.get("domain").get(0);
        assertThat(domain.has("current")).isTrue();
        assertThat(domain.has("previous")).isTrue();
        assertThat(domain.get("diagnoses").isArray()).isTrue();
        assertThat(domain.get("current").get("content").get("sourceMix").has("ownEntry")).isTrue();
        assertThat(body.get("composite").get(0).get("current").get("content").isNull()).isTrue();
        assertThat(body.get("composite").get(0).get("current").get("coverage").has("domainsCovered")).isTrue();
    }

    @Test
    void detailReturnsTrendCompositionAndTopContents() {
        long domainId = jdbc.queryForObject("SELECT id FROM channel WHERE name = '半导体'", Long.class);

        ResponseEntity<JsonNode> response = rest.getForEntity(
            url("/api/channels/" + domainId + "/detail?from=2019-06-01&to=2019-06-14&granularity=WEEK"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body.get("row").get("name").asText()).isEqualTo("半导体");
        assertThat(body.get("granularity").asText()).isEqualTo("WEEK");
        assertThat(body.get("trend")).hasSize(3);   // 05-27、06-03、06-10 三个周一开头的周
        assertThat(body.get("composition")).hasSize(5);
        assertThat(body.get("topContents").isArray()).isTrue();
    }

    @Test
    void detailDefaultsToDailyGranularity() {
        long id = jdbc.queryForObject("SELECT id FROM channel WHERE name = '推荐'", Long.class);

        JsonNode body = rest.getForEntity(
            url("/api/channels/" + id + "/detail?from=2019-06-01&to=2019-06-03"), JsonNode.class).getBody();

        assertThat(body.get("granularity").asText()).isEqualTo("DAY");
        assertThat(body.get("trend")).hasSize(3);
    }

    @Test
    void invalidRequestsGetA400WithAReason() {
        for (String path : new String[] {
            "/api/channels/cockpit?from=2019-06-30&to=2019-06-01",
            "/api/channels/cockpit?from=2019-01-01&to=2020-01-02",
            "/api/channels/cockpit?from=2019-6-1&to=2019-06-30",
            "/api/channels/cockpit?to=2019-06-30",
            "/api/channels/1/detail?from=2019-06-01&to=2019-06-30&granularity=HOUR"}) {
            ResponseEntity<String> response = get(path);
            assertThat(response.getStatusCode().value()).as(path).isEqualTo(400);
            assertThat(response.getBody()).as(path).isNotBlank();
        }
    }

    @Test
    void anUnknownChannelIs404() {
        assertThat(get("/api/channels/999999999/detail?from=2019-06-01&to=2019-06-30").getStatusCode().value())
            .isEqualTo(404);
    }
}
