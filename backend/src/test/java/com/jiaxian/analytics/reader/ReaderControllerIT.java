package com.jiaxian.analytics.reader;

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
class ReaderControllerIT {

    private static final String RANGE = "from=2019-06-03&to=2019-06-16";

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private JsonNode json(String path) {
        return rest.getForEntity(url(path), JsonNode.class).getBody();
    }

    @Test
    void activityReturnsSummaryAndOnePointPerPeriod() {
        JsonNode body = json("/api/readers/activity?" + RANGE + "&granularity=WEEK");

        assertThat(body.get("granularity").asText()).isEqualTo("WEEK");
        assertThat(body.get("points")).hasSize(2);
        assertThat(body.get("summary").has("dau")).isTrue();
        assertThat(body.get("summary").has("stickiness")).isTrue();
        assertThat(body.get("points").get(0).has("activeRate")).isTrue();
    }

    @Test
    void activityDefaultsToDailyPoints() {
        assertThat(json("/api/readers/activity?" + RANGE).get("points")).hasSize(14);
    }

    @Test
    void retentionFunnelSegmentsAndLurkersHaveTheirShapes() {
        JsonNode retention = json("/api/readers/retention?granularity=WEEK&" + RANGE + "&periods=3");
        assertThat(retention.get("periods").asInt()).isEqualTo(3);
        assertThat(retention.get("cohorts").isArray()).isTrue();
        for (JsonNode cohort : retention.get("cohorts")) {
            assertThat(cohort.get("retention")).hasSize(3);
        }

        JsonNode funnel = json("/api/readers/funnel?" + RANGE);
        assertThat(funnel.get("steps")).hasSize(5);
        assertThat(funnel.get("steps").get(0).get("key").asText()).isEqualTo("JOINED");

        JsonNode segments = json("/api/readers/segments?" + RANGE);
        assertThat(segments.get("segments")).hasSize(5);
        assertThat(segments.get("segments").get(2).get("role").asText()).isEqualTo("LURKER");
        assertThat(segments.get("segments").get(2).get("label").asText()).isEqualTo("沉默读者");

        JsonNode lurkers = json("/api/readers/lurkers?" + RANGE + "&limit=5");
        assertThat(lurkers.get("limit").asInt()).isEqualTo(5);
        assertThat(lurkers.get("lurkers").isArray()).isTrue();
        assertThat(lurkers.get("depts").isArray()).isTrue();
    }

    @Test
    void lurkerExportReturnsACsvWithBomAndHeader() {
        ResponseEntity<byte[]> response = rest.getForEntity(url("/api/readers/lurkers/export?" + RANGE), byte[].class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv");
        String body = new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).startsWith("\uFEFF员工ID,姓名,部门,加入社区,浏览次数,活跃天数,最近活跃\r\n");
        assertThat(rest.getForEntity(url("/api/readers/lurkers/export?from=2019-06-30&to=2019-06-01"), String.class)
            .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void invalidRequestsGetA400WithAReason() {
        for (String path : new String[] {
            "/api/readers/activity?from=2019-06-30&to=2019-06-01",
            "/api/readers/activity?from=2019-01-01&to=2020-01-02",
            "/api/readers/activity?from=2019-6-1&to=2019-06-30",
            "/api/readers/activity?to=2019-06-30",
            "/api/readers/activity?" + RANGE + "&granularity=HOUR",
            "/api/readers/retention?granularity=DAY&" + RANGE,
            "/api/readers/retention?" + RANGE,
            "/api/readers/retention?granularity=WEEK&" + RANGE + "&periods=13",
            "/api/readers/retention?granularity=WEEK&" + RANGE + "&periods=0",
            "/api/readers/lurkers?" + RANGE + "&limit=0",
            "/api/readers/lurkers?" + RANGE + "&limit=101",
            "/api/readers/lurkers?" + RANGE + "&offset=-1",
            "/api/readers/lurkers?" + RANGE + "&limit=abc"}) {
            ResponseEntity<String> response = rest.getForEntity(url(path), String.class);
            assertThat(response.getStatusCode().value()).as(path).isEqualTo(400);
            assertThat(response.getBody()).as(path).isNotBlank();
        }
    }

    @Test
    void rangeHasFromAndTo() {
        JsonNode body = json("/api/readers/range");

        assertThat(body.has("from")).isTrue();
        assertThat(body.has("to")).isTrue();
    }
}
