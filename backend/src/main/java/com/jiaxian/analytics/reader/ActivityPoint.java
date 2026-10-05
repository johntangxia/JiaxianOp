package com.jiaxian.analytics.reader;

import java.time.LocalDate;

/** 一个周期的活跃：周期内去重活跃人数（日 = DAU、周 = WAU、月 = MAU）、新增成员、期末成员数和活跃率。 */
public record ActivityPoint(LocalDate periodStart, long activeUsers, long newMembers, long members, Double activeRate) {
}
