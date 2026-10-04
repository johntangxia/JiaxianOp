package com.jiaxian.analytics.kol;

import java.time.LocalDate;

public record EmployeeInfo(long id, String name, String dept, LocalDate joinDate) {
}
