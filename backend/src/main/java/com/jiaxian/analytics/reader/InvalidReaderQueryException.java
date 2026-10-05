package com.jiaxian.analytics.reader;

/** 请求参数非法，对应 HTTP 400。 */
public class InvalidReaderQueryException extends RuntimeException {

    public InvalidReaderQueryException(String message) {
        super(message);
    }
}
