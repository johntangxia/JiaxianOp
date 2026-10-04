package com.jiaxian.analytics.kol;

import java.time.LocalDate;

public record PointsTrendPoint(LocalDate periodStart, long activePoints, long passivePoints, long total,
                               boolean complete) {
}
