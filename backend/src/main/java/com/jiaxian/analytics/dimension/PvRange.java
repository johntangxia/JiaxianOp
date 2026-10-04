package com.jiaxian.analytics.dimension;

import java.time.LocalDate;

/** pv_summary_daily 里有数据的日期范围；没有数据时两个值都是 null。 */
public record PvRange(LocalDate from, LocalDate to) {
}
