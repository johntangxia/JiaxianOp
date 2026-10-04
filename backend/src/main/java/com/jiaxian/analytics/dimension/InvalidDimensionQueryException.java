package com.jiaxian.analytics.dimension;

/** 请求参数非法，对应 HTTP 400。 */
public class InvalidDimensionQueryException extends RuntimeException {

    public InvalidDimensionQueryException(String message) {
        super(message);
    }
}
