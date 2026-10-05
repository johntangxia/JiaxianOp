package com.jiaxian.analytics.reader;

import java.time.LocalDate;

/** 某个员工在某一天有过任何行为（登录、浏览、点赞、评论、转发、收藏、发帖）。 */
public record ActiveDay(long employeeId, LocalDate day) {
}
