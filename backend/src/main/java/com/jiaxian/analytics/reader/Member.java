package com.jiaxian.analytics.reader;

import java.time.LocalDate;

/** 一个员工；joinDate 是加入社区的日期，为空的旧数据视为一直是成员。 */
public record Member(long id, String name, String dept, LocalDate joinDate) {

    /** 在 day 这一天是否已经是成员。 */
    public boolean isMemberOn(LocalDate day) {
        return joinDate == null || !joinDate.isAfter(day);
    }
}
