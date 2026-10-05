package com.jiaxian.analytics.people;

import java.time.LocalDate;
import java.util.List;

public record UserValueReport(LocalDate asOf, long members, List<SegmentRow> segments) {

    /** 每层的人数、占比，以及人均参与分（浏览 + 3×互动 + 10×发帖，近 90 天）和人均活跃天数。 */
    public record SegmentRow(ValueSegment segment, String label, long count, Double share, Double avgEngagement,
                             Double avgActiveDays) {
    }
}
