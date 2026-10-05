package com.jiaxian.analytics.contest;

/** 请求参数非法，对应 HTTP 400。 */
public class InvalidContestQueryException extends RuntimeException {

    public InvalidContestQueryException(String message) {
        super(message);
    }
}
