package com.jiaxian.analytics.kol;

/**
 * 名单内一位博主在统计日的各项原始指标。
 *
 * @param rank                名单内名次（1 起，不并列）
 * @param rollingPoints12m    近 12 个月积分
 * @param recencyDays         距最近一次发帖/评论/登录的天数；一次都没有则为 null
 * @param frequency           近 90 天发帖+评论+登录次数
 * @param monetary            近 90 天积分
 * @param recentActivity      近期（默认 30 天）活跃次数
 * @param baselineActivity    基线期（默认 90 天）活跃次数
 * @param recentPoints        近期积分
 * @param baselinePoints      基线期积分
 * @param previousRecentPoints 上一个近期（紧挨在近期之前、长度相同）的积分
 */
public record BloggerMetrics(long employeeId, int rank, long rollingPoints12m, Integer recencyDays,
                             int frequency, long monetary,
                             long recentActivity, long baselineActivity,
                             long recentPoints, long baselinePoints, long previousRecentPoints) {
}
