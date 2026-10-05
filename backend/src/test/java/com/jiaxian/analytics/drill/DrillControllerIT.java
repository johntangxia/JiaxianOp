package com.jiaxian.analytics.drill;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** 只检查接口形状和参数校验；数据口径由仓库、服务和端到端测试负责。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DrillControllerIT {

    private static final String RANGE = "from=2019-06-01&to=2019-06-14";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @LocalServerPort private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> get(String path) {
        return rest.getForEntity(url(path), String.class);
    }

    private long channelId(String name) {
        return jdbc.queryForObject("SELECT id FROM channel WHERE name = ?", Long.class, name);
    }

    @Test
    void channelAccountsHasTheExpectedShape() {
        JsonNode body = rest.getForEntity(url("/api/drill/channels/" + channelId("半导体") + "/accounts?" + RANGE),
            JsonNode.class).getBody();

        assertThat(body.get("channelName").asText()).isEqualTo("半导体");
        assertThat(body.get("previousTo").asText()).isEqualTo("2019-05-31");
        assertThat(body.get("accounts").isArray()).isTrue();
        for (JsonNode row : body.get("accounts")) {
            assertThat(row.get("current").get("content").get("sourceMix").has("ownEntry")).isTrue();
            assertThat(row.get("current").get("followers").has("followers")).isTrue();
            assertThat(row.get("current").get("exposure").has("impressions")).isTrue();
        }
    }

    @Test
    void aCompositeChannelCannotBeDrilledInto() {
        ResponseEntity<String> response = get("/api/drill/channels/" + channelId("推荐") + "/accounts?" + RANGE);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("Composite");
    }

    @Test
    void unknownObjectsAre404() {
        for (String path : new String[] {
            "/api/drill/channels/999999999/accounts?" + RANGE, "/api/drill/accounts/999999999?" + RANGE,
            "/api/drill/bloggers/999999999?" + RANGE, "/api/drill/contents/999999999?" + RANGE}) {
            assertThat(get(path).getStatusCode().value()).as(path).isEqualTo(404);
        }
    }

    @Test
    void invalidRequestsGetA400WithAReason() {
        long a = channelId("半导体");
        long b = channelId("光");
        for (String path : new String[] {
            "/api/drill/accounts/1?from=2019-06-30&to=2019-06-01",
            "/api/drill/accounts/1?from=2019-01-01&to=2020-01-02",
            "/api/drill/accounts/1?from=2019-6-1&to=2019-06-30",
            "/api/drill/accounts/1?to=2019-06-30",
            "/api/drill/accounts/abc?" + RANGE,
            "/api/drill/accounts/1?" + RANGE + "&granularity=HOUR",
            "/api/compare?type=CHANNEL&ids=" + a + "&" + RANGE,                           // 只有一个
            "/api/compare?type=CHANNEL&ids=" + a + "," + a + "&" + RANGE,                 // 重复
            "/api/compare?type=CHANNEL&ids=" + a + "," + channelId("推荐") + "&" + RANGE, // 综合频道
            "/api/compare?type=CONTENT&ids=1,2&" + RANGE,
            "/api/compare?type=SOMETHING&ids=" + a + "," + b + "&" + RANGE,
            "/api/compare?ids=" + a + "," + b + "&" + RANGE}) {
            ResponseEntity<String> response = get(path);
            assertThat(response.getStatusCode().value()).as(path).isEqualTo(400);
            assertThat(response.getBody()).as(path).isNotBlank();
        }
    }

    @Test
    void comparingTwoDomainChannelsReturnsBothInTheRequestedOrder() {
        long first = channelId("光");
        long second = channelId("半导体");

        JsonNode body = rest.getForEntity(url("/api/compare?type=CHANNEL&ids=" + first + "," + second + "&" + RANGE
            + "&granularity=WEEK"), JsonNode.class).getBody();

        assertThat(body.get("type").asText()).isEqualTo("CHANNEL");
        assertThat(body.get("subjects")).hasSize(2);
        assertThat(body.get("subjects").get(0).get("name").asText()).isEqualTo("光");
        assertThat(body.get("subjects").get(1).get("name").asText()).isEqualTo("半导体");
        assertThat(body.get("subjects").get(0).get("ownEntry").has("impressions")).isTrue();
        assertThat(body.get("subjects").get(0).get("trend")).hasSize(3);
    }
}
