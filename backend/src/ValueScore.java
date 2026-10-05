package com.jiaxian.analytics.people;

/** RFM 三个 1–5 分与分层结果。 */
public record ValueScore(long employeeId, int recency, int frequency, int monetary, ValueSegment segment) {
}
