package com.jiaxian.analytics.reader;

import java.time.LocalDate;
import java.util.List;

/** 一个同期群：加入周期的起点、人数，以及加入后第 0、1、2… 个周期的留存率（数据还没覆盖的周期为 null）。 */
public record CohortRow(LocalDate cohortStart, int size, List<Double> retention) {
}
