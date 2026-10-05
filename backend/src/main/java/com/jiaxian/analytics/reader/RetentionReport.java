package com.jiaxian.analytics.reader;

import com.jiaxian.analytics.pv.Granularity;

import java.time.LocalDate;
import java.util.List;

/** dataEnd 是最近一次有行为记录的日期：晚于它结束的周期还没被数据覆盖，留存为 null。 */
public record RetentionReport(LocalDate from, LocalDate to, Granularity granularity, int periods, LocalDate dataEnd,
                              List<CohortRow> cohorts) {
}
