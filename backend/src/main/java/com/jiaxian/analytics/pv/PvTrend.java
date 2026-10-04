package com.jiaxian.analytics.pv;

import java.time.LocalDate;
import java.util.List;

public record PvTrend(Granularity granularity, LocalDate from, LocalDate to,
                      List<PvPoint> points, List<ForecastPoint> forecast) {
}
