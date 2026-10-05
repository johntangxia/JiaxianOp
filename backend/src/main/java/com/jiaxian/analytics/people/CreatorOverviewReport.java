package com.jiaxian.analytics.people;

import com.jiaxian.analytics.pv.Granularity;
import com.jiaxian.analytics.reader.CohortRow;

import java.time.LocalDate;
import java.util.List;

/** 创作者总览：窗口汇总（对比上一个等长窗口）、周期序列，以及按首次发帖月分组的创作者留存。dataEnd 是最近一次行为的日期。 */
public record CreatorOverviewReport(LocalDate from, LocalDate to, Granularity granularity, CreatorSummary summary,
                                    List<CreatorPoint> series, int periods, LocalDate dataEnd, List<CohortRow> cohorts) {
}
