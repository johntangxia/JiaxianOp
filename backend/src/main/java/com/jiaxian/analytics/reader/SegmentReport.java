package com.jiaxian.analytics.reader;

import java.time.LocalDate;
import java.util.List;

public record SegmentReport(LocalDate from, LocalDate to, long members, List<SegmentCount> segments) {
}
