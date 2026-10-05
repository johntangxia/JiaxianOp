package com.jiaxian.analytics.drill;

/** 账号的关注者：期末仍在关注的人数、期间新增、期间取关。 */
public record FollowerMetrics(long followers, long newFollowers, long lostFollowers) {

    public static final FollowerMetrics EMPTY = new FollowerMetrics(0, 0, 0);
}
