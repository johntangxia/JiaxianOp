package com.jiaxian.analytics.people;

/**
 * 一个成员近 90 天的价值原始数据。
 *
 * @param daysSinceLast 距最近一次行为的天数（0–89），近 90 天没有行为为 null
 * @param activeDays    近 90 天活跃天数
 * @param engagement    参与分 = 浏览数 + 3 × 互动数 + 10 × 发帖数
 */
public record ValueInput(long employeeId, Integer daysSinceLast, int activeDays, double engagement) {
}
