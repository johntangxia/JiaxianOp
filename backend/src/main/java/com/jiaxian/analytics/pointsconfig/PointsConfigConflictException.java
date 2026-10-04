package com.jiaxian.analytics.pointsconfig;

/** 操作与现状冲突（比如删除某个动作仅剩的版本），对应 HTTP 409。 */
public class PointsConfigConflictException extends RuntimeException {

    public PointsConfigConflictException(String message) {
        super(message);
    }
}
