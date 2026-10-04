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
    /** 是否按博主画像（STAR/RISING/CHURNING/DORMANT/RANDOM）规划登录行为，默认 false，行为与以前完全一致。 */
    private boolean personas;
}
