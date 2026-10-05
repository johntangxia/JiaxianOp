package com.jiaxian.analytics.contest;

import java.time.LocalDate;
import java.util.List;

/** 一个周期的两个榜（领域榜、综合榜）和进步榜（领域榜里健康分提升最多的）。 */
public record ContestBoards(ContestType type, LocalDate periodStart, LocalDate periodEnd, LocalDate previousStart,
                            LocalDate previousEnd, boolean previousHasData, List<ContestEntry> domain,
                            List<ContestEntry> composite, List<ContestEntry> improvers) {
}
