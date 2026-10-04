package com.jiaxian.analytics.kol;

/** 请求参数非法，对应 HTTP 400。 */
public class InvalidKolQueryException extends RuntimeException {

    public InvalidKolQueryException(String message) {
        super(message);
    }
}
