package com.jiaxian.analytics.people;

import java.time.LocalDate;
import java.util.List;

/**
 * 生命周期：asOf 当前各阶段人数、上一个时点（asOf−30）各阶段人数，以及流转矩阵（行 = 上个时点的阶段，最后一行是"此前非成员"）。
 */
public record UserLifecycleReport(LocalDate asOf, LocalDate previousAsOf, long members, long previousMembers,
                                  List<StageRow> stages, List<TransitionView> transitions) {

    public record StageRow(LifecycleStage stage, String label, long count, Double share, long previousCount) {
    }

    /** from 为 null 表示此前还不是成员。 */
    public record TransitionView(LifecycleStage from, String fromLabel, long total, List<Cell> to) {
    }

    public record Cell(LifecycleStage stage, String label, long count) {
    }
}
