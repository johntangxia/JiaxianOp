package com.jiaxian.analytics.kol;

import java.time.LocalDate;

/** 标签变化点：asOf 是发生变化的那一期。 */
public record SegmentChange(LocalDate asOf, Segment from, Segment to) {
}
