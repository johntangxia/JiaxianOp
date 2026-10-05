package com.jiaxian.analytics.people;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** 只检查接口形状和参数校验；数据口径由服务和端到端测试负责。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserControllerIT {

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;

    private JsonNode json(String path) {
        return rest.getForEntity("http://localhost:" + port + path, JsonNode.class).getBody();
    }

    @Test
    void lifecycleAndValueHaveTheirShapes() {
        JsonNode lifecycle = json("/api/users/lifecycle?asOf=2019-06-30");
        assertThat(lifecycle.get("stages")).hasSize(6);
        assertThat(lifecycle.get("stages").get(0).get("stage").asText()).isEqualTo("NEW");
        assertThat(lifecycle.get("transitions")).hasSize(7);
        assertThat(lifecycle.get("transitions").get(6).get("from").isNull()).isTrue();
        assertThat(lifecycle.get("transitions").get(0).get("to")).hasSize(6);
        assertThat(lifecycle.get("previousAsOf").asText()).isEqualTo("2019-05-31");
        assertThat(json("/api/users/lifecycle").get("asOf").asText()).isNotBlank();

        JsonNode value = json("/api/users/value?asOf=2019-06-30");
        assertThat(value.get("segments")).hasSize(5);
        assertThat(value.get("segments").get(0).get("segment").asText()).isEqualTo("CORE");
        assertThat(value.get("segments").get(4).get("label").asText()).isEqualTo("沉睡");
    }

    @Test
    void interestsAndListHaveTheirShapes() {
        JsonNode interests = json("/api/users/interests?asOf=2019-06-30&days=30");
        assertThat(interests.get("days").asInt()).isEqualTo(30);
        assertThat(interests.get("depts").isArray()).isTrue();

        JsonNode list = json("/api/users/list?asOf=2019-06-30&sort=VIEWS&limit=5");
        assertThat(list.get("limit").asInt()).isEqualTo(5);
        assertThat(list.get("users").isArray()).isTrue();
        assertThat(list.get("depts").isArray()).isTrue();
        assertThat(list.get("total").asLong()).isGreaterThanOrEqualTo(list.get("users").size());
        assertThat(json("/api/users/list?asOf=2019-06-30&q=不存在的名字xyz").get("total").asLong()).isZero();
    }

    @Test
    void exportReturnsACsvWithBomHeaderAndOneRowPerUser() {
        ResponseEntity<byte[]> response = rest.getForEntity("http://localhost:" + port
            + "/api/users/export?asOf=2019-06-30&sort=VIEWS", byte[].class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).startsWith("attachment");
        String body = new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).startsWith("\uFEFF员工ID,姓名,部门,加入社区,生命周期,价值分层,最近活跃");
        long members = json("/api/users/list?asOf=2019-06-30&limit=1").get("total").asLong();
        assertThat(body.split("\r\n")).hasSize((int) members + 1);
        assertThat(rest.getForEntity("http://localhost:" + port + "/api/users/export?stage=SLEEPY", String.class)
            .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void unknownEmployeeIs404AndBadRequestsAre400() {
        ResponseEntity<String> missing = rest.getForEntity("http://localhost:" + port + "/api/users/-5", String.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(missing.getBody()).isNotBlank();

        for (String path : new String[] {
            "/api/users/lifecycle?asOf=2019-6-1",
            "/api/users/value?asOf=abc",
            "/api/users/interests?days=6",
            "/api/users/interests?days=181",
            "/api/users/list?stage=SLEEPY",
            "/api/users/list?value=GOLD",
            "/api/users/list?sort=NAME",
            "/api/users/list?limit=0",
            "/api/users/list?limit=101",
            "/api/users/list?offset=-1",
            "/api/users/abc"}) {
            ResponseEntity<String> response = rest.getForEntity("http://localhost:" + port + path, String.class);
            assertThat(response.getStatusCode().value()).as(path).isEqualTo(400);
            assertThat(response.getBody()).as(path).isNotBlank();
        }
    }
}
