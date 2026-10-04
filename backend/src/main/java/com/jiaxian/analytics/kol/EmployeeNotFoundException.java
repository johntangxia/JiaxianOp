package com.jiaxian.analytics.kol;

/** 员工不存在，对应 HTTP 404。 */
public class EmployeeNotFoundException extends RuntimeException {

    public EmployeeNotFoundException(long id) {
        super("Employee " + id + " does not exist");
    }
}
