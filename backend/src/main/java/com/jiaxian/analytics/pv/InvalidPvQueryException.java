package com.jiaxian.analytics.pv;

/** 请求参数非法，对应 HTTP 400。 */
public class InvalidPvQueryException extends RuntimeException {

    public InvalidPvQueryException(String message) {
        super(message);
    }
}
