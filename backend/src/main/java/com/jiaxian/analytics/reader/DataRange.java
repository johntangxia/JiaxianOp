package com.jiaxian.analytics.reader;

import java.time.LocalDate;

/** 数据的日期范围；没有数据时 from/to 都是 null。 */
public record DataRange(LocalDate from, LocalDate to) {
}
