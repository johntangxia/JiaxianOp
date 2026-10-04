package com.jiaxian.analytics.pv;

import java.time.LocalDate;

/** 一个周期的 PV。complete=false 表示周期还没结束（最后一天不早于今天）。 */
public record PvBucket(LocalDate periodStart, long pv, boolean complete) {
}
