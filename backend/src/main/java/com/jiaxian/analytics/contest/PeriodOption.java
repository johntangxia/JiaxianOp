package com.jiaxian.analytics.contest;

import java.time.LocalDate;

/** 一个可选的竞赛周期；complete 表示它已经完整过去（以 PV 数据的最后一天为"现在"）。 */
public record PeriodOption(LocalDate periodStart, LocalDate periodEnd, boolean complete) {
}
