package com.jiaxian.analytics.growth;

import java.util.List;
import java.util.Map;

/** 告警的一页；counts 是各状态的告警总数（不受筛选影响）。 */
public record AlertList(long total, int limit, int offset, Map<AlertStatus, Long> counts, List<GrowthAlert> alerts) {
}
