package com.jiaxian.analytics.reader;

import com.jiaxian.analytics.pv.Granularity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 纯逻辑：新成员留存同期群。同期群 = 加入日期落在 [from, to] 内、按加入周/月分组的成员；
 * 第 k 列 = 加入后第 k 个周期里至少活跃一天的人占同期群的比例；周期还没完整地被数据覆盖时（结束日晚于 dataEnd）为 null。
 */
public final class CohortBuilder {

    private CohortBuilder() {
    }

    public static List<CohortRow> build(List<Member> members, List<ActiveDay> activeDays, LocalDate from, LocalDate to,
                                        Granularity granularity, int periods, LocalDate dataEnd) {
        Map<Long, NavigableSet<LocalDate>> daysByMember = new HashMap<>();
        for (ActiveDay day : activeDays) {
            daysByMember.computeIfAbsent(day.employeeId(), k -> new TreeSet<>()).add(day.day());
        }

        Map<LocalDate, List<Member>> cohorts = new TreeMap<>();
        for (Member member : members) {
            if (member.joinDate() != null && !member.joinDate().isBefore(from) && !member.joinDate().isAfter(to)) {
                cohorts.computeIfAbsent(granularity.periodStart(member.joinDate()), k -> new ArrayList<>()).add(member);
            }
        }

        List<CohortRow> rows = new ArrayList<>();
        cohorts.forEach((cohortStart, cohort) -> {
            List<Double> retention = new ArrayList<>();
            LocalDate periodStart = cohortStart;
            for (int k = 0; k < periods; k++) {
                LocalDate periodEnd = granularity.periodEnd(periodStart);
                if (periodEnd.isAfter(dataEnd)) {
                    retention.add(null);
                } else {
                    final LocalDate start = periodStart;
                    long active = cohort.stream().filter(m -> hasActivity(daysByMember.get(m.id()), start, periodEnd)).count();
                    retention.add(active / (double) cohort.size());
                }
                periodStart = granularity.nextPeriodStart(periodStart);
            }
            rows.add(new CohortRow(cohortStart, cohort.size(), retention));
        });
        return rows;
    }

    private static boolean hasActivity(NavigableSet<LocalDate> days, LocalDate from, LocalDate to) {
        if (days == null) {
            return false;
        }
        LocalDate first = days.ceiling(from);
        return first != null && !first.isAfter(to);
    }
}
