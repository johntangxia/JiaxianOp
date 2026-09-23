package com.jiaxian.analytics.simulator;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class SimulationConfig {
    private String batchId;
    private int employeeCount;
    private int accountCount;
    private double teamAccountRatio;
    private int contentCount;
    private LocalDate historyStart;
    private LocalDate historyEnd;
    private long randomSeed;
}
