package com.jiaxian.analytics.simulator;

import lombok.Getter;
import lombok.Setter;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin/simulator")
public class SimulatorController {

    private final HistoricalBackfillService backfillService;

    public SimulatorController(HistoricalBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @PostMapping("/backfill")
    public String backfill(@RequestBody BackfillRequest request) {
        SimulationConfig config = SimulationConfig.builder()
            .batchId(request.getBatchId())
            .employeeCount(request.getEmployeeCount())
            .accountCount(request.getAccountCount())
            .teamAccountRatio(request.getTeamAccountRatio())
            .contentCount(request.getContentCount())
            .historyStart(request.getHistoryStart())
            .historyEnd(request.getHistoryEnd())
            .randomSeed(request.getRandomSeed())
            .personas(request.isPersonas())
            .build();

        backfillService.runBackfill(config);
        return "Backfill completed for batch: " + request.getBatchId();
    }

    @Getter @Setter
    public static class BackfillRequest {
        private String batchId;
        private int employeeCount;
        private int accountCount;
        private double teamAccountRatio;
        private int contentCount;
        private LocalDate historyStart;
        private LocalDate historyEnd;
        private long randomSeed;
        private boolean personas;
    }
}
