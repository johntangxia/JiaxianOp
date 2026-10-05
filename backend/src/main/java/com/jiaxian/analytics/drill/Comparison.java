package com.jiaxian.analytics.drill;

import com.jiaxian.analytics.pv.Granularity;

import java.time.LocalDate;
import java.util.List;

public record Comparison(SubjectType type, LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo,
                         Granularity granularity, List<CompareSubject> subjects) {
}
