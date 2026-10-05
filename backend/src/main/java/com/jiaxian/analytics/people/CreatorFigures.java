package com.jiaxian.analytics.people;

/** 一个创作者在窗口内的产出：发帖数和 PV（用来算集中度）。 */
public record CreatorFigures(long employeeId, long posts, long pv) {
}
