package com.jiaxian.analytics.pv;

import java.time.LocalDate;

/** 某一天的 PV（来自汇总表）。 */
public record DailyPv(LocalDate date, long pv) {
}
