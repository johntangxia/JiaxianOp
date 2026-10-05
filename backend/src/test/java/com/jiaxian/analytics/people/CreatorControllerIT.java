package com.jiaxian.analytics.people;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** 只检查接口形状和参数校验；数据口径由仓库、服务和端到端测试负责。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CreatorControllerIT {

    private static final String RANGE = "from=2019-06-03&to=2019-08-30";

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;

    private JsonNode json(String path) {
        return rest.getForEntity("http://localhost:" + port + path, JsonNode.class).getBody();
    }

    @Test
    void overviewHasSummarySeriesAndCohorts() {
        JsonNode body = json("/api/creators/overview?" + RANGE);

        assertThat(body.get("granularity").asText()).isEqualTo("MONTH");
        assertThat(body.get("series")).hasSize(3);
        assertThat(body.get("summary").has("lostCreators")).isTrue();
        assertThat(body.get("summary").has("returningCreators")).isTrue();
        assertThat(body.get("periods").asInt()).isEqualTo(6);
        for (JsonNode cohort : body.get("cohorts")) {
            assertThat(cohort.get("retention")).hasSize(6);
        }
        assertThat(json("/api/creators/overview?" + RANGE + "&periods=3").get("periods").asInt()).isEqualTo(3);
    }

    @Test
    void rankingLadderAndConcentrationHaveTheirShapes() {
        JsonNode ranking = json("/api/creators/ranking?" + RANGE + "&sort=POSTS&limit=5");
        assertThat(ranking.get("sort").asText()).isEqualTo("POSTS");
        assertThat(ranking.get("limit").asInt()).isEqualTo(5);
        assertThat(ranking.get("creators").isArray()).isTrue();
        assertThat(ranking.get("depts").isArray()).isTrue();

        JsonNode ladder = json("/api/creators/ladder?asOf=2019-06-30");
        assertThat(ladder.get("stages")).hasSize(6);
        assertThat(ladder.get("stages").get(5).get("stage").asText()).isEqualTo("KOL");
        assertThat(ladder.get("reach")).hasSize(3);
        assertThat(json("/api/creators/ladder").get("asOf").asText()).isNotBlank();

        JsonNode concentration = json("/api/creators/concentration?" + RANGE);
        assertThat(concentration.get("shares")).hasSize(4);
        assertThat(concentration.get("accountTypes")).hasSize(2);
        assertThat(concentration.get("accountTypes").get(1).get("type").asText()).isEqualTo("TEAM");
    }

    @Test
    void rankingExportReturnsACsvWithBomAndHeader() {
        ResponseEntity<byte[]> response = rest.getForEntity("http://localhost:" + port
            + "/api/creators/ranking/export?" + RANGE + "&sort=POSTS", byte[].class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv");
        String body = new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).startsWith("\uFEFF排名,员工ID,姓名,部门,发帖数,PV,UV,互动数,互动率,读完率,新增关注\r\n");
        assertThat(rest.getForEntity("http://localhost:" + port + "/api/creators/ranking/export?" + RANGE + "&sort=NAME",
            String.class).getStatusCode().value()).isEqualTo(400);
        assertThat(rest.getForEntity("http://localhost:" + port + "/api/creators/ranking/export?to=2019-06-30",
            String.class).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void invalidRequestsGetA400WithAReason() {
        for (String path : new String[] {
            "/api/creators/overview?from=2019-06-30&to=2019-06-01",
            "/api/creators/overview?from=2019-01-01&to=2020-01-02",
            "/api/creators/overview?to=2019-06-30",
            "/api/creators/overview?" + RANGE + "&granularity=YEAR",
            "/api/creators/overview?" + RANGE + "&granularity=HOUR",
            "/api/creators/overview?" + RANGE + "&periods=13",
            "/api/creators/ranking?" + RANGE + "&sort=NAME",
            "/api/creators/ranking?" + RANGE + "&limit=0",
            "/api/creators/ranking?" + RANGE + "&limit=101",
            "/api/creators/ranking?" + RANGE + "&offset=-1",
            "/api/creators/ladder?asOf=2019-6-1",
            "/api/creators/concentration?from=2019-06-30&to=2019-06-01"}) {
            ResponseEntity<String> response = rest.getForEntity("http://localhost:" + port + path, String.class);
            assertThat(response.getStatusCode().value()).as(path).isEqualTo(400);
            assertThat(response.getBody()).as(path).isNotBlank();
        }
    }
}
