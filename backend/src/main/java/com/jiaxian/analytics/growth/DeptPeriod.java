package com.jiaxian.analytics.growth;

/** 一个部门在一个窗口里的读者侧情况。渗透率 = 活跃成员 / 成员数，没有成员时为 null。 */
public record DeptPeriod(long members, long active, Double penetration, long creators, long interactors, long lurkers,
                         long newMembers) {
}
