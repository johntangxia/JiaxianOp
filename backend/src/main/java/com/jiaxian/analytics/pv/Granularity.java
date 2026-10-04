package com.jiaxian.analytics.pv;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/** 趋势分析的时间粒度。周一为一周起点（ISO），月取 1 号，年取 1 月 1 日。 */
public enum Granularity {
    DAY(7), WEEK(4), MONTH(3), YEAR(2);

    private final int forecastWindow;

    Granularity(int forecastWindow) {
        this.forecastWindow = forecastWindow;
    }

    /** 预测用的移动平均窗口（以本粒度的周期个数计）。 */
    public int forecastWindow() {
        return forecastWindow;
    }

    /** date 所在周期的第一天。 */
    public LocalDate periodStart(LocalDate date) {
        return switch (this) {
            case DAY -> date;
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
            case YEAR -> date.withDayOfYear(1);
        };
    }

    /** 以 periodStart 开头的周期的最后一天。 */
    public LocalDate periodEnd(LocalDate periodStart) {
        return switch (this) {
            case DAY -> periodStart;
            case WEEK -> periodStart.plusDays(6);
            case MONTH -> periodStart.with(TemporalAdjusters.lastDayOfMonth());
            case YEAR -> periodStart.with(TemporalAdjusters.lastDayOfYear());
        };
    }

    public LocalDate nextPeriodStart(LocalDate periodStart) {
        return periodEnd(periodStart).plusDays(1);
    }

    /** 去年同期的周期起点：DAY/YEAR 取一年前，WEEK 取 52 周前，MONTH 取 12 个月前。 */
    public LocalDate sameLastYear(LocalDate periodStart) {
        return switch (this) {
            case DAY, YEAR -> periodStart.minusYears(1);
            case WEEK -> periodStart.minusWeeks(52);
            case MONTH -> periodStart.minusMonths(12);
        };
    }
}
