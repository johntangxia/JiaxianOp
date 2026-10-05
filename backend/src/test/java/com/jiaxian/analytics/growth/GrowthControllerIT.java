package com.jiaxian.analytics.growth;

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
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 接口形状、参数校验和任务/告警的增删改流程；2019 年没有数据，所以不会生成新的告警。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GrowthControllerIT {

    private static final String ASOF = "asOf=2019-06-30";

    @Autowired private TestRestTemplate rest;
    @Autowired private GrowthAlertRepository alertRepository;
    @LocalServerPort private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> send(HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url(path), method, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void departmentsAndHotContentsHaveTheirShapes() {
        JsonNode departments = rest.getForEntity(url("/api/growth/departments?" + ASOF + "&days=30"), JsonNode.class).getBody();
        assertThat(departments.get("days").asInt()).isEqualTo(30);
        assertThat(departments.get("to").asText()).isEqualTo("2019-06-30");
        assertThat(departments.get("previousTo").asText()).isEqualTo("2019-05-31");
        assertThat(departments.get("departments").isArray()).isTrue();
        assertThat(departments.get("overall").get("dept").asText()).isEqualTo("全社区");
        assertThat(departments.get("overall").get("current").has("penetration")).isTrue();

        JsonNode hot = rest.getForEntity(url("/api/growth/hot-contents?" + ASOF + "&limit=5"), JsonNode.class).getBody();
        assertThat(hot.get("hot").isArray()).isTrue();
        assertThat(hot.get("potential").isArray()).isTrue();
    }

    @Test
    void alertsListHasCountsPerStatusAndRebuildIsHarmlessOnEmptyData() {
        JsonNode list = rest.getForEntity(url("/api/growth/alerts?limit=5"), JsonNode.class).getBody();
        assertThat(list.get("limit").asInt()).isEqualTo(5);
        assertThat(list.get("counts").has("OPEN")).isTrue();
        assertThat(list.get("counts").has("ACKED")).isTrue();
        assertThat(list.get("counts").has("CLOSED")).isTrue();
        assertThat(list.get("alerts").isArray()).isTrue();

        JsonNode rebuild = rest.postForEntity(url("/api/admin/growth/alerts/rebuild?" + ASOF), null, JsonNode.class).getBody();
        assertThat(rebuild.get("asOf").asText()).isEqualTo("2019-06-30");
        assertThat(rebuild.get("created").asInt()).isZero();
        assertThat(rebuild.get("total").asInt()).isZero();
    }

    @Test
    void anAlertStatusCanBeChangedAndBadRequestsAreRejected() {
        GrowthAlert alert = alertRepository.save(GrowthAlert.builder().kind("IT测试_状态").severity("WARN").entityType(GrowthEntityType.OVERALL)
            .entityName("全社区").periodEnd(LocalDate.of(2019, 6, 30)).message("m").status(AlertStatus.OPEN)
            .createdAt(LocalDateTime.of(2019, 6, 30, 9, 0)).updatedAt(LocalDateTime.of(2019, 6, 30, 9, 0)).build());
        try {
            ResponseEntity<String> ok = send(HttpMethod.PUT, "/api/admin/growth/alerts/" + alert.getId() + "/status", Map.of("status", "ACKED"));
            assertThat(ok.getStatusCode().value()).isEqualTo(200);
            assertThat(alertRepository.findById(alert.getId()).orElseThrow().getStatus()).isEqualTo(AlertStatus.ACKED);

            assertThat(send(HttpMethod.PUT, "/api/admin/growth/alerts/" + alert.getId() + "/status", Map.of("status", "BOGUS")).getStatusCode().value()).isEqualTo(400);
            assertThat(send(HttpMethod.PUT, "/api/admin/growth/alerts/" + alert.getId() + "/status", Map.of()).getStatusCode().value()).isEqualTo(400);
            assertThat(send(HttpMethod.PUT, "/api/admin/growth/alerts/999999999/status", Map.of("status", "CLOSED")).getStatusCode().value()).isEqualTo(404);
        } finally {
            alertRepository.deleteById(alert.getId());
        }
    }

    @Test
    void aTaskCanBeCreatedTrackedUpdatedAndDeleted() {
        ResponseEntity<String> created = send(HttpMethod.POST, "/api/admin/growth/tasks", Map.of(
            "title", "IT测试任务", "taskType", "ONBOARD_NEWCOMERS", "entityType", "OVERALL", "metric", "ACTIVE_RATE",
            "targetValue", 0.8, "owner", "测试人", "startDate", "2019-06-30", "dueDate", "2019-07-30", "note", "备注"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        JsonNode view = parse(created.getBody());
        long id = view.get("task").get("id").asLong();
        try {
            assertThat(view.get("task").get("status").asText()).isEqualTo("TODO");
            assertThat(view.get("task").get("entityName").asText()).isEqualTo("全社区");
            assertThat(view.get("task").get("startDate").asText()).startsWith("2019-06-30");
            assertThat(view.get("task").has("baselineValue")).isTrue();

            JsonNode list = rest.getForEntity(url("/api/growth/tasks?status=TODO&limit=100"), JsonNode.class).getBody();
            boolean listed = false;
            for (JsonNode item : list.get("tasks")) {
                listed |= item.get("task").get("id").asLong() == id;
            }
            assertThat(listed).isTrue();

            JsonNode detail = rest.getForEntity(url("/api/growth/tasks/" + id), JsonNode.class).getBody();
            assertThat(detail.get("series")).hasSize(8);
            assertThat(detail.get("view").has("currentValue")).isTrue();
            assertThat(detail.get("view").has("progress")).isTrue();

            ResponseEntity<String> updated = send(HttpMethod.PUT, "/api/admin/growth/tasks/" + id, Map.of("status", "DOING", "owner", "新负责人"));
            assertThat(updated.getStatusCode().value()).isEqualTo(200);
            assertThat(parse(updated.getBody()).get("task").get("status").asText()).isEqualTo("DOING");
            assertThat(parse(updated.getBody()).get("task").get("ownerName").asText()).isEqualTo("新负责人");
        } finally {
            assertThat(send(HttpMethod.DELETE, "/api/admin/growth/tasks/" + id, null).getStatusCode().value()).isEqualTo(204);
        }
        assertThat(rest.getForEntity(url("/api/growth/tasks/" + id), String.class).getStatusCode().value()).isEqualTo(404);
        assertThat(send(HttpMethod.DELETE, "/api/admin/growth/tasks/" + id, null).getStatusCode().value()).isEqualTo(404);
    }

    private JsonNode parse(String body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void invalidRequestsGetA400WithAReason() {
        for (String path : new String[] {
            "/api/growth/departments?days=6", "/api/growth/departments?days=91", "/api/growth/departments?asOf=2999-01-01",
            "/api/growth/departments?asOf=2019-6-30", "/api/growth/hot-contents?limit=0", "/api/growth/hot-contents?limit=101",
            "/api/growth/alerts?limit=0", "/api/growth/alerts?offset=-1", "/api/growth/alerts?status=BOGUS",
            "/api/growth/alerts?entityType=BOGUS", "/api/growth/tasks?limit=101", "/api/growth/tasks?status=BOGUS",
            "/api/growth/tasks/abc"}) {
            ResponseEntity<String> response = rest.getForEntity(url(path), String.class);
            assertThat(response.getStatusCode().value()).as(path).isEqualTo(400);
            assertThat(response.getBody()).as(path).isNotBlank();
        }
    }

    @Test
    void invalidTaskBodiesAreRejected() {
        for (Map<String, Object> body : java.util.List.<Map<String, Object>>of(
            Map.of("entityType", "OVERALL", "metric", "ACTIVE_RATE", "targetValue", 1.0),                       // 没有标题
            Map.of("title", "t", "entityType", "OVERALL", "metric", "PV", "targetValue", 1.0),                  // 指标不匹配
            Map.of("title", "t", "entityType", "OVERALL", "metric", "ACTIVE_RATE"),                             // 没有目标值
            Map.of("title", "t", "entityType", "NOPE", "metric", "ACTIVE_RATE", "targetValue", 1.0),            // 枚举非法
            Map.of("title", "t", "entityType", "OVERALL", "metric", "ACTIVE_RATE", "targetValue", 1.0, "startDate", "2019-6-30"),
            Map.of("title", "t", "entityType", "CHANNEL", "entityId", 999999999, "metric", "PV", "targetValue", 1.0))) {
            ResponseEntity<String> response = send(HttpMethod.POST, "/api/admin/growth/tasks", body);
            assertThat(response.getStatusCode().value()).as(body.toString()).isEqualTo(400);
            assertThat(response.getBody()).isNotBlank();
        }
        assertThat(send(HttpMethod.POST, "/api/admin/growth/tasks", null).getStatusCode().value()).isEqualTo(400);
    }
}
