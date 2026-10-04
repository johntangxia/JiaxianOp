package com.jiaxian.analytics.pv;

import java.time.LocalDate;

public record ForecastPoint(LocalDate periodStart, double value, double lower, double upper) {
}
