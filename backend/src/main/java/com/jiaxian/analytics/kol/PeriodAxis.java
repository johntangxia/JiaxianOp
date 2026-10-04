package com.jiaxian.analytics.kol;

import com.jiaxian.analytics.pv.Granularity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯逻辑：按粒度生成连续周期。from 向前扩展到所在周期起点，to 超过今天按今天算，
 * 周期的最后一天早于今天才算已结束；周期数超过 maxPeriods 立即停止并报 400（不会先生成再检查）。
 */
public final class PeriodAxis {

    public record Period(LocalDate start, boolean complete) {
    }

    private PeriodAxis() {
    }

    public static List<Period> periods(Granularity granularity, LocalDate from, LocalDate to,
                                       LocalDate today, int maxPeriods) {
        LocalDate effectiveTo = to.isAfter(today) ? today : to;
        if (from.isAfter(effectiveTo)) {
            return List.of();
        }
        LocalDate last = granularity.periodStart(effectiveTo);
        List<Period> periods = new ArrayList<>();
        for (LocalDate start = granularity.periodStart(from); !start.isAfter(last);
             start = granularity.nextPeriodStart(start)) {
            if (periods.size() >= maxPeriods) {
                throw new InvalidKolQueryException("range covers more than " + maxPeriods + " periods");
            }
            periods.add(new Period(start, granularity.periodEnd(start).isBefore(today)));
        }
        return periods;
    }
}
