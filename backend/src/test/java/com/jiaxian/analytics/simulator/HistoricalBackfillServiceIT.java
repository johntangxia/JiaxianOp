package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class HistoricalBackfillServiceIT {

    @Autowired private HistoricalBackfillService backfillService;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private PvLogRepository pvLogRepository;
    @Autowired private PointsLedgerRepository pointsLedgerRepository;

    @Test
    void runBackfillPopulatesDataAndRerunClearsPreviousBatch() {
        SimulationConfig firstRun = SimulationConfig.builder()
            .batchId("batch-1")
            .employeeCount(20)
            .accountCount(10)
            .teamAccountRatio(0.2)
            .contentCount(30)
            .historyStart(LocalDate.of(2026, 1, 1))
            .historyEnd(LocalDate.of(2026, 3, 1))
            .randomSeed(1)
            .build();

        backfillService.runBackfill(firstRun);

        assertThat(employeeRepository.count()).isEqualTo(20);
        assertThat(contentRepository.count()).isEqualTo(30);
        assertThat(pvLogRepository.count()).isGreaterThan(0);
        assertThat(pointsLedgerRepository.count()).isGreaterThan(0);

        SimulationConfig secondRun = SimulationConfig.builder()
            .batchId("batch-2")
            .employeeCount(15)
            .accountCount(8)
            .teamAccountRatio(0.2)
            .contentCount(20)
            .historyStart(LocalDate.of(2026, 1, 1))
            .historyEnd(LocalDate.of(2026, 3, 1))
            .randomSeed(2)
            .build();

        backfillService.runBackfill(secondRun);

        assertThat(employeeRepository.count()).isEqualTo(15);
        assertThat(contentRepository.count()).isEqualTo(20);
        assertThat(employeeRepository.findAll()).allMatch(e -> "batch-2".equals(e.getSimulationBatchId()));
    }
}
