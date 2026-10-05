package com.jiaxian.analytics.growth;

/** 规则算出来的一条告警（还没落库）。 */
public record AlertCandidate(String kind, String severity, GrowthEntityType entityType, Long entityId, String entityName,
                             String message) {
}
