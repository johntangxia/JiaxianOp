package com.jiaxian.analytics.people;

/**
 * 窗口对比：创作者数、新增（首次发帖在本窗口）、流失（上期发过、本期没发）、回流（本期发、上期没发、更早发过）、
 * 创作者占成员比、发帖总数，以及上期的创作者数和发帖数。
 */
public record CreatorSummary(long creators, long newCreators, long lostCreators, long returningCreators, Double creatorShare,
                             long posts, long previousCreators, long previousPosts) {
}
