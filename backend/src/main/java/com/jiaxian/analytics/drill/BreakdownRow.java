package com.jiaxian.analytics.drill;

/** 下钻的一行：下一级对象（账号、作者、频道……）当期和上期的指标；extra 是补充说明（账号类型、频道类别、部门）。 */
public record BreakdownRow(long id, String name, String extra, SubjectMetrics current, SubjectMetrics previous) {
}
