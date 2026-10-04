package com.jiaxian.analytics.kol;

import java.time.LocalDate;

/** 员工在最近一次重算里的状态；alert 只在触发预警时有值。 */
public record CurrentStanding(LocalDate asOf, int rank, Segment segment, String segmentName, boolean churnAlert,
                              String recommendation, long rollingPoints12m, Integer recencyDays,
                              int frequency, long monetary, int rScore, int fScore, int mScore,
                              AlertDetail alert) {
}
