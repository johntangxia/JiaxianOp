package com.jiaxian.analytics.drill;

/** 对象不存在，对应 HTTP 404。 */
public class DrillNotFoundException extends RuntimeException {

    public DrillNotFoundException(String kind, long id) {
        super(kind + " not found: " + id);
    }
}
