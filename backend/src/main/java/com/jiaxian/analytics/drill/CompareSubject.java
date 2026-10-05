package com.jiaxian.analytics.drill;

import com.jiaxian.analytics.channel.DistributionMetrics;

import java.util.List;

/** 对比里的一个对象。ownEntry 只有频道有：该频道自己入口的分发（同频道驾驶舱）。 */
public record CompareSubject(long id, String name, String extra, SubjectMetrics current, SubjectMetrics previous,
                             DistributionMetrics ownEntry, List<DrillTrendPoint> trend) {
}
