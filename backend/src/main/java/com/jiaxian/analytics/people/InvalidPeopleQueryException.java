package com.jiaxian.analytics.people;

/** 请求参数非法，对应 HTTP 400。 */
public class InvalidPeopleQueryException extends RuntimeException {

    public InvalidPeopleQueryException(String message) {
        super(message);
    }
}
