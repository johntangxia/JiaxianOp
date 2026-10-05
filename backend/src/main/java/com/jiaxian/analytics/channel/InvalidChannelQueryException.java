package com.jiaxian.analytics.channel;

/** 请求参数非法，对应 HTTP 400。 */
public class InvalidChannelQueryException extends RuntimeException {

    public InvalidChannelQueryException(String message) {
        super(message);
    }
}
