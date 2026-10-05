package com.jiaxian.analytics.growth;

import java.time.LocalDate;
import java.util.List;

public record DepartmentReport(LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo, int days,
                               List<DepartmentRow> departments, DepartmentRow overall) {
}
