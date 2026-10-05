package com.jiaxian.analytics.growth;

import com.jiaxian.analytics.simulator.HistoricalBackfillService;
import com.jiaxian.analytics.simulator.SimulationConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class GrowthTablesIT {

    private static final LocalDate DAY = LocalDate.of(2019, 6, 30);

    @Autowired private GrowthAlertRepository alertRepository;
    @Autowired private GrowthTaskRepository taskRepository;
    @Autowired private HistoricalBackfillService backfillService;

    private GrowthAlert alert(String kind, String name) {
        return GrowthAlert.builder().kind(kind).severity(GrowthAlert.WARN).entityType(GrowthEntityType.CHANNEL).entityId(1L)
            .entityName(name).periodEnd(DAY).message("测试告警").status(AlertStatus.OPEN)
            .createdAt(LocalDateTime.of(2019, 6, 30, 9, 0)).updatedAt(LocalDateTime.of(2019, 6, 30, 9, 0)).build();
    }

    private GrowthTask task() {
        return GrowthTask.builder().title("增长表测试任务").taskType(TaskType.IMPROVE_OWN_ENTRY).entityType(GrowthEntityType.CHANNEL)
            .entityId(1L).entityName("增长表测试频道").metric(TaskMetric.OWN_ENTRY_SHARE.name()).baselineValue(0.02).targetValue(0.05)
            .ownerName("测试负责人").startDate(DAY).dueDate(DAY.plusDays(30)).status(TaskStatus.TODO).note("备注")
            .createdAt(LocalDateTime.of(2019, 6, 30, 9, 0)).updatedAt(LocalDateTime.of(2019, 6, 30, 9, 0)).build();
    }

    @Test
    void alertsRoundTripAndAreUniquePerKindEntityAndPeriod() {
        GrowthAlert saved = alertRepository.saveAndFlush(alert("CHANNEL_LOW_OWN_ENTRY_SHARE", "增长表测试频道"));

        GrowthAlert loaded = alertRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getEntityType()).isEqualTo(GrowthEntityType.CHANNEL);
        assertThat(loaded.getStatus()).isEqualTo(AlertStatus.OPEN);
        assertThat(loaded.getPeriodEnd()).isEqualTo(DAY);
        assertThat(alertRepository.findByKindAndEntityTypeAndEntityNameAndPeriodEnd(
            "CHANNEL_LOW_OWN_ENTRY_SHARE", GrowthEntityType.CHANNEL, "增长表测试频道", DAY)).isPresent();

        assertThatThrownBy(() -> alertRepository.saveAndFlush(alert("CHANNEL_LOW_OWN_ENTRY_SHARE", "增长表测试频道")))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSameKindOnAnotherEntityOrDayIsAllowed() {
        alertRepository.saveAndFlush(alert("X", "频道甲"));
        alertRepository.saveAndFlush(alert("X", "频道乙"));
        GrowthAlert nextDay = alert("X", "频道甲");
        nextDay.setPeriodEnd(DAY.plusDays(1));
        alertRepository.saveAndFlush(nextDay);

        assertThat(alertRepository.findAll().stream().filter(a -> a.getKind().equals("X"))).hasSize(3);
    }

    @Test
    void tasksRoundTrip() {
        GrowthTask saved = taskRepository.saveAndFlush(task());

        GrowthTask loaded = taskRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getTaskType()).isEqualTo(TaskType.IMPROVE_OWN_ENTRY);
        assertThat(loaded.getMetric()).isEqualTo("OWN_ENTRY_SHARE");
        assertThat(loaded.getBaselineValue()).isEqualTo(0.02);
        assertThat(loaded.getTargetValue()).isEqualTo(0.05);
        assertThat(loaded.getStartDate()).isEqualTo(DAY);
        assertThat(loaded.getDueDate()).isEqualTo(DAY.plusDays(30));
        assertThat(loaded.getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(loaded.getOwnerName()).isEqualTo("测试负责人");
    }

    @Test
    void metricsKnowWhichEntityTypesTheyApplyTo() {
        assertThat(TaskMetric.OWN_ENTRY_SHARE.appliesTo(GrowthEntityType.CHANNEL)).isTrue();
        assertThat(TaskMetric.OWN_ENTRY_SHARE.appliesTo(GrowthEntityType.ACCOUNT)).isFalse();
        assertThat(TaskMetric.PENETRATION.appliesTo(GrowthEntityType.DEPARTMENT)).isTrue();
        assertThat(TaskMetric.ACTIVE_RATE.appliesTo(GrowthEntityType.OVERALL)).isTrue();
        assertThat(TaskMetric.ACTIVE_USERS.appliesTo(GrowthEntityType.DEPARTMENT)).isTrue();
        assertThat(TaskMetric.PV.appliesTo(GrowthEntityType.OVERALL)).isFalse();
    }

    @Test
    void aHistoricalBackfillClearsAlertsAndTasksThatPointAtTheOldSimulation() {
        alertRepository.saveAndFlush(alert("CHANNEL_X", "回填清理测试频道"));
        taskRepository.saveAndFlush(task());

        backfillService.runBackfill(SimulationConfig.builder().batchId("growth-cleanup").employeeCount(6).accountCount(3)
            .teamAccountRatio(0.3).contentCount(6).historyStart(LocalDate.of(2025, 1, 1)).historyEnd(LocalDate.of(2025, 2, 1))
            .randomSeed(3).build());

        assertThat(alertRepository.count()).isZero();
        assertThat(taskRepository.count()).isZero();
    }
}
