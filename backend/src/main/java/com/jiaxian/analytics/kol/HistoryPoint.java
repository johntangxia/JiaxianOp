package com.jiaxian.analytics.kol;

import java.time.LocalDate;

/** 某次每周重算里这位员工的一行（用来画 RFM 分数和标签的历史走势）。 */
public record HistoryPoint(LocalDate asOf, int rank, Segment segment, String segmentName, boolean churnAlert,
                           int rScore, int fScore, int mScore) {
}
