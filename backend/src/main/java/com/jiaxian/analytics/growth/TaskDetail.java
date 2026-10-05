package com.jiaxian.analytics.growth;

import java.time.LocalDate;
import java.util.List;

public record TaskDetail(TaskView view, LocalDate asOf, List<EffectPoint> series) {
}
