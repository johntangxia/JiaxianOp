package com.jiaxian.analytics.people;

import java.time.LocalDateTime;

/**
 * 一个成员截至统计日的累计行为：首次行为（任何）、首次浏览、首次互动、首次发帖的时刻（没有为 null），
 * 近 90 天发帖数，以及最近一次行为的时刻。
 */
public record LifetimeFacts(long employeeId, LocalDateTime firstActivity, LocalDateTime firstView,
                            LocalDateTime firstInteraction, LocalDateTime firstPost, long recentPosts,
                            LocalDateTime lastActivity) {
}
