package com.jiaxian.analytics.growth;

import com.jiaxian.analytics.domain.*;
import com.jiaxian.analytics.growth.GrowthQueryRepository.AlertPage;
import com.jiaxian.analytics.growth.GrowthQueryRepository.TaskPage;
import com.jiaxian.analytics.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class GrowthQueryRepositoryIT {

    private static final String BATCH = "growth-query-it";
    private static final LocalDate DAY = LocalDate.of(2019, 6, 30);

    @Autowired private GrowthQueryRepository repository;
    @Autowired private GrowthAlertRepository alertRepository;
    @Autowired private GrowthTaskRepository taskRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private JdbcTemplate jdbc;

    private GrowthAlert alert(String kind, String severity, GrowthEntityType type, AlertStatus status, int minute) {
        return alertRepository.save(GrowthAlert.builder().kind(kind).severity(severity).entityType(type).entityId(1L)
            .entityName("查询测试对象").periodEnd(DAY).message("消息").status(status)
            .createdAt(LocalDateTime.of(2019, 6, 30, 9, minute)).updatedAt(LocalDateTime.of(2019, 6, 30, 9, minute)).build());
    }

    private GrowthTask task(String title, TaskStatus status) {
        return taskRepository.save(GrowthTask.builder().title(title).taskType(TaskType.OTHER).entityType(GrowthEntityType.OVERALL)
            .metric("ACTIVE_RATE").targetValue(0.5).startDate(DAY).status(status)
            .createdAt(LocalDateTime.of(2019, 6, 30, 9, 0)).updatedAt(LocalDateTime.of(2019, 6, 30, 9, 0)).build());
    }

    @Test
    void alertsAreSortedBySeverityThenNewestAndCanBeFiltered() {
        alert("IT测试_A", "WARN", GrowthEntityType.CHANNEL, AlertStatus.OPEN, 10);
        alert("IT测试_B", "CRITICAL", GrowthEntityType.OVERALL, AlertStatus.OPEN, 5);
        alert("IT测试_C", "WARN", GrowthEntityType.CHANNEL, AlertStatus.CLOSED, 20);
        alert("IT测试_D", "WARN", GrowthEntityType.CHANNEL, AlertStatus.OPEN, 30);

        AlertPage open = repository.alerts(AlertStatus.OPEN, null, null, 100, 0);
        List<String> kinds = open.alerts().stream().map(GrowthAlert::getKind).filter(k -> k.startsWith("IT测试_")).toList();
        assertThat(kinds).containsExactly("IT测试_B", "IT测试_D", "IT测试_A");   // 严重在前，同级别新的在前

        assertThat(repository.alerts(null, "IT测试_C", null, 10, 0).alerts()).extracting(GrowthAlert::getStatus)
            .containsExactly(AlertStatus.CLOSED);
        assertThat(repository.alerts(null, null, GrowthEntityType.OVERALL, 100, 0).alerts()).extracting(GrowthAlert::getKind)
            .contains("IT测试_B").doesNotContain("IT测试_A");
    }

    @Test
    void alertPagesReportTheTotalAndCountsPerStatus() {
        Map<AlertStatus, Long> before = repository.alertCounts();
        alert("IT测试_E", "WARN", GrowthEntityType.CHANNEL, AlertStatus.OPEN, 1);
        alert("IT测试_F", "WARN", GrowthEntityType.CHANNEL, AlertStatus.ACKED, 2);

        AlertPage page = repository.alerts(null, "IT测试_E", null, 1, 0);
        Map<AlertStatus, Long> after = repository.alertCounts();

        assertThat(page.total()).isEqualTo(1);
        assertThat(after.get(AlertStatus.OPEN)).isEqualTo(before.get(AlertStatus.OPEN) + 1);
        assertThat(after.get(AlertStatus.ACKED)).isEqualTo(before.get(AlertStatus.ACKED) + 1);
        assertThat(after.get(AlertStatus.CLOSED)).isEqualTo(before.get(AlertStatus.CLOSED));
        // offset 超出时没有行但总数不变
        assertThat(repository.alerts(null, "IT测试_E", null, 10, 5).alerts()).isEmpty();
        assertThat(repository.alerts(null, "IT测试_E", null, 10, 5).total()).isEqualTo(1);
    }

    @Test
    void tasksAreListedNewestFirstAndFilteredByStatus() {
        GrowthTask first = task("查询测试任务一", TaskStatus.TODO);
        GrowthTask second = task("查询测试任务二", TaskStatus.DOING);
        GrowthTask third = task("查询测试任务三", TaskStatus.TODO);

        TaskPage todo = repository.tasks(TaskStatus.TODO, 100, 0);
        assertThat(todo.tasks()).extracting(GrowthTask::getId).containsSubsequence(third.getId(), first.getId());
        assertThat(todo.tasks()).extracting(GrowthTask::getId).doesNotContain(second.getId());

        TaskPage all = repository.tasks(null, 1, 0);
        assertThat(all.tasks()).hasSize(1);
        assertThat(all.tasks().get(0).getId()).isEqualTo(third.getId());
        assertThat(all.total()).isGreaterThanOrEqualTo(3);
        assertThat(all.tasks().get(0).getTargetValue()).isEqualTo(0.5);
        assertThat(all.tasks().get(0).getBaselineValue()).isNull();
        assertThat(all.tasks().get(0).getEntityId()).isNull();
        assertThat(all.tasks().get(0).getStartDate()).isEqualTo(DAY);
    }

    @Test
    void churnRisksComeFromTheLatestRebuildNotAfterTheGivenDay() {
        Employee a = employeeRepository.save(Employee.builder().name("增长查询员工甲").simulationBatchId(BATCH).build());
        Employee b = employeeRepository.save(Employee.builder().name("增长查询员工乙").simulationBatchId(BATCH).build());
        Employee c = employeeRepository.save(Employee.builder().name("增长查询员工丙").simulationBatchId(BATCH).build());
        rfm(LocalDate.of(2019, 6, 23), a, 5, "CHURN_RISK");
        rfm(LocalDate.of(2019, 6, 30), a, 9, "CHURN_RISK");
        rfm(LocalDate.of(2019, 6, 30), b, 3, "CHURN_RISK");
        rfm(LocalDate.of(2019, 6, 30), c, 1, "STAR_KOL");

        List<ChurnRisk> latest = repository.churnRisks(DAY, 10);
        assertThat(latest).extracting(ChurnRisk::employeeId).containsExactly(b.getId(), a.getId());   // 按名次，丙不是流失预警
        assertThat(latest.get(0).name()).isEqualTo("增长查询员工乙");
        assertThat(latest.get(0).rank()).isEqualTo(3);

        List<ChurnRisk> older = repository.churnRisks(LocalDate.of(2019, 6, 25), 10);
        assertThat(older).extracting(ChurnRisk::employeeId).containsExactly(a.getId());
        assertThat(older.get(0).rank()).isEqualTo(5);
        assertThat(repository.churnRisks(DAY, 1)).hasSize(1);
    }

    private void rfm(LocalDate asOf, Employee employee, int rank, String segment) {
        jdbc.update("INSERT INTO blogger_rfm_score (as_of_date, employee_id, rank_no, rolling_points_12m, recency_days, frequency,"
            + " monetary, r_score, f_score, m_score, segment_label, churn_alert) VALUES (?, ?, ?, 100, 5, 3, 100, 3, 3, 3, ?, ?)",
            Timestamp.valueOf(asOf.atStartOfDay()), employee.getId(), rank, segment, segment.equals("CHURN_RISK"));
    }

    @Test
    void contentChannelsMapEveryContentToItsChannel() {
        Channel channel = channelRepository.save(Channel.builder().name("增长查询频道").category("领域频道").build());
        Account account = accountRepository.save(Account.builder().name("增长查询账号").type(AccountType.PERSONAL).simulationBatchId(BATCH).build());
        Content content = contentRepository.save(Content.builder().type(ContentType.ARTICLE).channelId(channel.getId())
            .accountId(account.getId()).publishTime(LocalDateTime.of(2019, 6, 3, 9, 0)).popularityWeight(0.5)
            .simulationBatchId(BATCH).build());

        assertThat(repository.contentChannels()).containsEntry(content.getId(), channel.getId());
    }
}
