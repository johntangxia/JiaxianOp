package com.jiaxian.analytics.kol;

/** 创建/更新建议文案的请求体。 */
public record PlaybookRequest(Segment segmentLabel, PlaybookAlertStatus alertStatus, String recommendationText) {
}
