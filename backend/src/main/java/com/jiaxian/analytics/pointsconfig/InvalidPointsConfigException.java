package com.jiaxian.analytics.pointsconfig;

/** 请求参数非法，对应 HTTP 400。 */
public class InvalidPointsConfigException extends RuntimeException {

    public InvalidPointsConfigException(String message) {
        super(message);
    }
}
