package com.jiaxian.analytics.kol;

import com.jiaxian.analytics.pv.Granularity;

import java.time.LocalDate;
import java.util.List;

public record PointsTrend(Granularity granularity, LocalDate from, LocalDate to, List<PointsTrendPoint> points) {
}
