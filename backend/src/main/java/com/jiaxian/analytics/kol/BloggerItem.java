package com.jiaxian.analytics.kol;

/** 名单接口返回的一位博主：原始指标、三个分数、分层标签、预警标记和匹配的建议文案。 */
public record BloggerItem(long employeeId, String name, int rank, long rollingPoints12m, Integer recencyDays,
                          int frequency, long monetary, int rScore, int fScore, int mScore,
                          Segment segment, String segmentName, boolean churnAlert, String recommendation) {
}
