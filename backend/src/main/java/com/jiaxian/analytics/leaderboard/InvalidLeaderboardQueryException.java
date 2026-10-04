package com.jiaxian.analytics.leaderboard;

/** 请求参数非法，对应 HTTP 400。 */
public class InvalidLeaderboardQueryException extends RuntimeException {

    public InvalidLeaderboardQueryException(String message) {
        super(message);
    }
}
