package com.jiaxian.analytics.pv;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把"按天 PV"聚成连续的周期序列：from 向前扩展到所在周期的起点，to 向后扩展到所在周期的终点
 * （但 to 超过今天时按今天算），没有数据的周期补 0。
 */
public final class PvSeriesBuilder {

    private PvSeriesBuilder() {
    }

    public static List<PvBucket> build(List<DailyPv> daily, Granularity granularity,
                                       LocalDate from, LocalDate to, LocalDate today) {
        LocalDate effectiveTo = to.isAfter(today) ? today : to;
        if (from.isAfter(effectiveTo)) {
            return List.of();
        }

        Map<LocalDate, Long> sumByPeriod = new HashMap<>();
        for (DailyPv day : daily) {
            sumByPeriod.merge(granularity.periodStart(day.date()), day.pv(), Long::sum);
        }

        List<PvBucket> buckets = new ArrayList<>();
        LocalDate lastPeriod = granularity.periodStart(effectiveTo);
        for (LocalDate start = granularity.periodStart(from); !start.isAfter(lastPeriod);
             start = granularity.nextPeriodStart(start)) {
            boolean complete = granularity.periodEnd(start).isBefore(today);
            buckets.add(new PvBucket(start, sumByPeriod.getOrDefault(start, 0L), complete));
        }
        return buckets;
    }
}
