package com.jiaxian.analytics.kol;

import com.fasterxml.jackson.databind.JsonNode;
import com.jiaxian.analytics.domain.ActiveActionType;
import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.domain.PointsCategory;
import com.jiaxian.analytics.repository.ActiveActionLogRepository;
import com.jiaxian.analytics.repository.EmployeeRepository;
import com.jiaxian.analytics.repository.PointsLedgerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BloggerProfileControllerIT {

    private static final long MISSING = 999_999_999L;

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ActiveActionLogRepository activeActionLogRepository;
    @Autowired private PointsLedgerRepository pointsLedgerRepository;

    private Employee x;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static LocalDateTime t(int month, int day, int hour) {
        return LocalDateTime.of(2019, month, day, hour, 0);
    }

    /** 这个测试走真实 HTTP，不能靠事务回滚：自己创建员工和流水，结束时自己删。 */
    @BeforeEach
    void setUp() {
        KolTestFixtures f = new KolTestFixtures(employeeRepository, activeActionLogRepository, pointsLedgerRepository);
        x = f.employee("KOL测试员工HTTP");
        f.action(x, ActiveActionType.POST, t(6, 3, 9));
        f.action(x, ActiveActionType.COMMENT, t(6, 4, 10));
        f.action(x, ActiveActionType.LOGIN, t(6, 4, 11));
        f.action(x, ActiveActionType.LOGIN, t(6, 4, 12));
        f.action(x, ActiveActionType.LOGIN, t(6, 10, 8));
        f.ledger(x, PointsCategory.ACTIVE, 100, t(6, 3, 12));
        f.ledger(x, PointsCategory.ACTIVE, 20, t(6, 4, 12));
        f.ledger(x, PointsCategory.PASSIVE, 15, t(6, 4, 13));
        f.ledger(x, PointsCategory.PASSIVE, 5, t(6, 11, 12));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM active_action_log WHERE employee_id = ?", x.getId());
        jdbc.update("DELETE FROM points_ledger WHERE employee_id = ?", x.getId());
        jdbc.update("DELETE FROM employee WHERE id = ?", x.getId());
    }

    private void assertStatus(String path, int status, String messagePart) {
        ResponseEntity<String> response = rest.getForEntity(url(path), String.class);
        assertThat(response.getStatusCode().value()).as(path).isEqualTo(status);
        assertThat(response.getBody()).as(path).contains(messagePart);
    }

    @Test
    void profileOfAnEmployeeOutsideTheRankingHasNoCurrentStanding() {
        ResponseEntity<JsonNode> response = rest.getForEntity(url("/api/bloggers/" + x.getId() + "/profile"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body.get("employee").get("id").asLong()).isEqualTo(x.getId());
        assertThat(body.get("employee").get("name").asText()).isEqualTo("KOL测试员工HTTP");
        assertThat(body.get("current").isNull()).isTrue();
        assertThat(body.get("history")).isEmpty();
        assertThat(body.get("segmentChanges")).isEmpty();
        assertThat(body.get("recentEvents")).hasSize(5);
        assertThat(body.get("recentEvents").get(0).get("type").asText()).isEqualTo("LOGIN");
        assertThat(body.get("recentEvents").get(0).get("time").asText()).startsWith("2019-06-10T08:00");
    }

    @Test
    void activityReturnsZeroFilledWeeklyCounts() {
        ResponseEntity<JsonNode> response = rest.getForEntity(
            url("/api/bloggers/" + x.getId() + "/activity?granularity=WEEK&from=2019-06-01&to=2019-06-30"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body.get("granularity").asText()).isEqualTo("WEEK");
        assertThat(body.get("from").asText()).isEqualTo("2019-06-01");
        assertThat(body.get("to").asText()).isEqualTo("2019-06-30");
        // 06-01 是周六，向前扩展到周一 05-27；共 5 周：05-27、06-03、06-10、06-17、06-24
        assertThat(body.get("points")).hasSize(5);
        assertThat(body.get("points").get(0).get("periodStart").asText()).isEqualTo("2019-05-27");
        assertThat(body.get("points").get(0).get("logins").asLong()).isZero();
        JsonNode week = body.get("points").get(1);
        assertThat(week.get("periodStart").asText()).isEqualTo("2019-06-03");
        assertThat(week.get("posts").asLong()).isEqualTo(1);
        assertThat(week.get("comments").asLong()).isEqualTo(1);
        assertThat(week.get("logins").asLong()).isEqualTo(2);
        assertThat(week.get("complete").asBoolean()).isTrue();
        assertThat(body.get("points").get(2).get("logins").asLong()).isEqualTo(1);
    }

    @Test
    void pointsTrendSplitsActiveAndPassive() {
        ResponseEntity<JsonNode> response = rest.getForEntity(
            url("/api/bloggers/" + x.getId() + "/points-trend?granularity=MONTH&from=2019-06-01&to=2019-06-30"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode points = response.getBody().get("points");
        assertThat(points).hasSize(1);
        assertThat(points.get(0).get("periodStart").asText()).isEqualTo("2019-06-01");
        assertThat(points.get(0).get("activePoints").asLong()).isEqualTo(120);
        assertThat(points.get(0).get("passivePoints").asLong()).isEqualTo(20);
        assertThat(points.get(0).get("total").asLong()).isEqualTo(140);
    }

    @Test
    void unknownEmployeesAreNotFound() {
        assertStatus("/api/bloggers/" + MISSING + "/profile", 404, "does not exist");
        assertStatus("/api/bloggers/" + MISSING + "/activity", 404, "does not exist");
        assertStatus("/api/bloggers/" + MISSING + "/points-trend", 404, "does not exist");
    }

    @Test
    void badParametersAreRejected() {
        long id = x.getId();
        assertStatus("/api/bloggers/abc/profile", 400, "id");
        assertStatus("/api/bloggers/" + id + "/profile?weeks=0", 400, "weeks");
        assertStatus("/api/bloggers/" + id + "/profile?weeks=105", 400, "weeks");
        assertStatus("/api/bloggers/" + id + "/activity?granularity=HOURLY", 400, "granularity");
        assertStatus("/api/bloggers/" + id + "/activity?from=2019-6-1", 400, "from");
        assertStatus("/api/bloggers/" + id + "/activity?from=2019-06-05&to=2019-06-01", 400, "from must not be after to");
        assertStatus("/api/bloggers/" + id + "/activity?granularity=DAY&from=2010-01-01&to=2019-06-01", 400, "400 periods");
        assertStatus("/api/bloggers/" + id + "/points-trend?granularity=DAY&from=2010-01-01&to=2019-06-01", 400, "400 periods");
    }
}
