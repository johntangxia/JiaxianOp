package com.jiaxian.analytics.kol;

public record PlaybookEntry(long id, Segment segmentLabel, PlaybookAlertStatus alertStatus,
                            String recommendationText) {
}
