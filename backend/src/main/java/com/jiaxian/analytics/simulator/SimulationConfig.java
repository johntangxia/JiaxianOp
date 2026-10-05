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
    /**
     * 每个登录日平均产生的浏览次数（浏览由登录产生，见 {@link ViewPlanner}）。2.0 对应约 27 万 PV / 1000 员工 / 18 个月；
     * 数据量或回填耗时过大时调低（1.0 约减半）。
     */
    @Builder.Default
    private double viewsPerActiveDay = DEFAULT_VIEWS_PER_ACTIVE_DAY;

    public static final double DEFAULT_VIEWS_PER_ACTIVE_DAY = 2.0;
}
