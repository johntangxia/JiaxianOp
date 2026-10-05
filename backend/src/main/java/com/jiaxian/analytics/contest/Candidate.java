package com.jiaxian.analytics.contest;

import java.util.Map;

/** 参赛频道及它的各项指标原值；缺失或为 null 的指标按 0 处理。 */
public record Candidate(long channelId, Map<String, Double> metrics) {

    double metric(String key) {
        Double value = metrics.get(key);
        return value == null ? 0 : value;
    }
}
