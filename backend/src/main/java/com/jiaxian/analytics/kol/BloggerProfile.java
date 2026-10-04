package com.jiaxian.analytics.kol;

import java.util.List;

/**
 * 博主详情。current 为 null 表示该员工不在最近一次重算的名单里（或还没有任何重算记录）。
 */
public record BloggerProfile(EmployeeInfo employee, CurrentStanding current, List<HistoryPoint> history,
                             List<SegmentChange> segmentChanges, List<RecentEvent> recentEvents) {
}
