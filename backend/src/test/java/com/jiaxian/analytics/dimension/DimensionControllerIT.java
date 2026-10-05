package com.jiaxian.analytics.dimension;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DimensionControllerIT {

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void channelsEndpointReturnsTheSeededChannels() {
        ResponseEntity<JsonNode> response = rest.getForEntity(url("/api/dimensions/channels"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().isArray()).isTrue();
        assertThat(response.getBody().size()).isGreaterThanOrEqualTo(28);
        JsonNode first = response.getBody().get(0);
        assertThat(first.has("id")).isTrue();
        assertThat(first.has("name")).isTrue();
        assertThat(first.has("category")).isTrue();
    }

    @Test
    void accountAndEmployeeSearchesReturnArrays() {
        ResponseEntity<JsonNode> accounts = rest.getForEntity(
            url("/api/dimensions/accounts?keyword=不存在的维度关键字&limit=5"), JsonNode.class);
        ResponseEntity<JsonNode> employees = rest.getForEntity(
            url("/api/dimensions/employees?limit=3"), JsonNode.class);

        assertThat(accounts.getStatusCode().value()).isEqualTo(200);
        assertThat(accounts.getBody().isArray()).isTrue();
        assertThat(accounts.getBody()).isEmpty();
        assertThat(employees.getStatusCode().value()).isEqualTo(200);
        assertThat(employees.getBody().size()).isLessThanOrEqualTo(3);
    }

    @Test
    void limitOutsideTheSafeRangeIsRejected() {
        for (String path : new String[] {"/api/dimensions/accounts?limit=0", "/api/dimensions/accounts?limit=101",
            "/api/dimensions/employees?limit=0", "/api/dimensions/employees?limit=abc"}) {
            ResponseEntity<String> response = rest.getForEntity(url(path), String.class);
            assertThat(response.getStatusCode().value()).as(path).isEqualTo(400);
            assertThat(response.getBody()).as(path).contains("limit");
        }
    }

    @Test
    void pvRangeEndpointReturnsFromAndToKeys() {
        ResponseEntity<JsonNode> response = rest.getForEntity(url("/api/meta/pv-range"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().has("from")).isTrue();
        assertThat(response.getBody().has("to")).isTrue();
    }
}
