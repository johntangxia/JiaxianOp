package com.jiaxian.analytics.reader;

import com.jiaxian.analytics.pv.Granularity;

import java.time.LocalDate;
import java.util.List;

public record ActivityReport(LocalDate from, LocalDate to, Granularity granularity, ActivitySummary summary,
                             List<ActivityPoint> points) {
}
