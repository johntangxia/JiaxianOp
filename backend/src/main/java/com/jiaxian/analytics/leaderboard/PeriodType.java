package com.jiaxian.analytics.leaderboard;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/** 榜单周期：月（yyyy-MM）或年（yyyy）。区间是左闭右开 [起点, 下一周期起点)。 */
public enum PeriodType {
    MONTH, YEAR;

    private static final Pattern MONTH_FORMAT = Pattern.compile("\\d{4}-\\d{2}");
    private static final Pattern YEAR_FORMAT = Pattern.compile("\\d{4}");

    /** 把 "2026-09" / "2026" 解析成周期起点。 */
    public LocalDate parse(String value) {
        try {
            return switch (this) {
                case MONTH -> {
                    if (!MONTH_FORMAT.matcher(value).matches()) {
                        throw new DateTimeParseException("bad month", value, 0);
                    }
                    yield YearMonth.parse(value).atDay(1);
                }
                case YEAR -> {
                    if (!YEAR_FORMAT.matcher(value).matches()) {
                        throw new DateTimeParseException("bad year", value, 0);
                    }
                    yield LocalDate.of(Integer.parseInt(value), 1, 1);
                }
            };
        } catch (DateTimeParseException e) {
            throw new InvalidLeaderboardQueryException(
                "period must look like " + (this == MONTH ? "yyyy-MM" : "yyyy"));
        }
    }

    /** 周期起点 → "2026-09" / "2026"。 */
    public String format(LocalDate periodStart) {
        return switch (this) {
            case MONTH -> String.format("%04d-%02d", periodStart.getYear(), periodStart.getMonthValue());
            case YEAR -> String.format("%04d", periodStart.getYear());
        };
    }

    /** date 所在周期的起点。 */
    public LocalDate containing(LocalDate date) {
        return switch (this) {
            case MONTH -> date.withDayOfMonth(1);
            case YEAR -> date.withDayOfYear(1);
        };
    }

    public LocalDate nextStart(LocalDate periodStart) {
        return switch (this) {
            case MONTH -> periodStart.plusMonths(1);
            case YEAR -> periodStart.plusYears(1);
        };
    }

    /** 路径变量只接受小写 month / year。 */
    public static PeriodType fromPath(String path) {
        return switch (path) {
            case "month" -> MONTH;
            case "year" -> YEAR;
            default -> throw new InvalidLeaderboardQueryException("leaderboard type must be 'month' or 'year'");
        };
    }
}
