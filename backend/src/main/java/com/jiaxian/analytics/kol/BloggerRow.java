package com.jiaxian.analytics.kol;

/** blogger_rfm_score 联员工表的一行。 */
public record BloggerRow(long employeeId, String name, int rank, long rollingPoints12m, Integer recencyDays,
                         int frequency, long monetary, int rScore, int fScore, int mScore,
                         Segment segment, boolean churnAlert) {
}
