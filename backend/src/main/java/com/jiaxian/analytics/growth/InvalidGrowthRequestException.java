package com.jiaxian.analytics.growth;

/** 请求参数非法，对应 HTTP 400。 */
public class InvalidGrowthRequestException extends RuntimeException {

    public InvalidGrowthRequestException(String message) {
        super(message);
    }
}
