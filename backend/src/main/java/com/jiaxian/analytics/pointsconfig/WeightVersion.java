package com.jiaxian.analytics.pointsconfig;

import java.time.LocalDate;

public record WeightVersion(long id, int weight, LocalDate effectiveDate) {
}
