package com.jiaxian.analytics.people;

/** 员工不存在，对应 HTTP 404。 */
public class PeopleNotFoundException extends RuntimeException {

    public PeopleNotFoundException(long id) {
        super("Employee not found: " + id);
    }
}
