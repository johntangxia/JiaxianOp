package com.jiaxian.analytics.drill;

/** 请求参数非法，对应 HTTP 400。 */
public class InvalidDrillQueryException extends RuntimeException {

    public InvalidDrillQueryException(String message) {
        super(message);
    }
}
