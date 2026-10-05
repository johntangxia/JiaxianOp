package com.jiaxian.analytics.people;

import java.time.LocalDate;

/** 某个员工某一天的发帖数。 */
public record PostDay(long employeeId, LocalDate day, long posts) {
}
