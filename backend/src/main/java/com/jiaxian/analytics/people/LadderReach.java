package com.jiaxian.analytics.people;

/** 成员走到某一步（首次浏览/互动/发帖）的人数、占成员比，以及从加入社区到那一步的中位天数（只统计走到的人）。 */
public record LadderReach(String step, String label, long reached, Double share, Double medianDays) {
}
