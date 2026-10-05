package com.jiaxian.analytics.reader;

import java.time.LocalDate;
import java.util.List;

public record FunnelReport(LocalDate from, LocalDate to, long joined, List<FunnelStep> steps) {
}
