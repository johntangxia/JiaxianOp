package com.jiaxian.analytics.kol;

import java.time.LocalDate;

/** 一次重算的结果：统计日、名单人数、触发预警的人数。 */
public record KolRebuildResult(LocalDate asOf, int population, int alerts) {
}
