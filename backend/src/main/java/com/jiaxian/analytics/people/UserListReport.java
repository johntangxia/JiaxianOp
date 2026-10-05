package com.jiaxian.analytics.people;

import java.time.LocalDate;
import java.util.List;

public record UserListReport(LocalDate asOf, long total, int limit, int offset, List<UserRow> users, List<String> depts) {

    /** 阶段是 asOf 当天的生命周期；活跃天数、浏览/互动/发帖是近 90 天的。 */
    public record UserRow(long employeeId, String name, String dept, LocalDate joinDate, LifecycleStage stage, String stageLabel,
                          ValueSegment valueSegment, String valueLabel, LocalDate lastActive, long activeDays, long views,
                          long interactions, long posts) {
    }
}
