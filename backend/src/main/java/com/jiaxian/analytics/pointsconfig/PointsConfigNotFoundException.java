package com.jiaxian.analytics.pointsconfig;

/** 动作类型或版本不存在，对应 HTTP 404。 */
public class PointsConfigNotFoundException extends RuntimeException {

    public PointsConfigNotFoundException(String message) {
        super(message);
    }
}
