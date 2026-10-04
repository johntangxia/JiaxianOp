package com.jiaxian.analytics.kol;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * @param asOf          统计日；没有任何重算记录时为 null
 * @param total         筛选后的总人数
 * @param segmentCounts 该统计日未筛选的各分层人数（每个分层都有键，没有人则为 0）
 */
public record BloggerList(LocalDate asOf, long total, Map<Segment, Long> segmentCounts, List<BloggerItem> items) {
}
