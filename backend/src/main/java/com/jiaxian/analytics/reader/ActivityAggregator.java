package com.jiaxian.analytics.reader;

import com.jiaxian.analytics.pv.Granularity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 纯逻辑：把 (员工, 日) 的活跃记录按周期归并成活跃人数、新增成员、活跃率和 DAU/WAU/MAU 汇总。 */
public final class ActivityAggregator {

    static final int WAU_DAYS = 7;
    static final int MAU_DAYS = 30;

    private ActivityAggregator() {
    }

    /** 区间 [from, to] 内每个周期一个点（补零）；周期两端按区间裁剪。 */
    public static List<ActivityPoint> series(List<Member> members, List<ActiveDay> activeDays, LocalDate from,
                                             LocalDate to, Granularity granularity) {
        Map<LocalDate, Set<Long>> activeByBucket = new TreeMap<>();
        for (LocalDate start = granularity.periodStart(from); !start.isAfter(to); start = granularity.nextPeriodStart(start)) {
            activeByBucket.put(start, new HashSet<>());
        }
        for (ActiveDay day : activeDays) {
            if (!day.day().isBefore(from) && !day.day().isAfter(to)) {
                activeByBucket.get(granularity.periodStart(day.day())).add(day.employeeId());
            }
        }

        List<ActivityPoint> points = new ArrayList<>();
        activeByBucket.forEach((start, active) -> {
            LocalDate bucketFrom = start.isBefore(from) ? from : start;
            LocalDate bucketTo = granularity.periodEnd(start).isAfter(to) ? to : granularity.periodEnd(start);
            long newMembers = members.stream()
                .filter(m -> m.joinDate() != null && !m.joinDate().isBefore(bucketFrom) && !m.joinDate().isAfter(bucketTo)).count();
            long total = members.stream().filter(m -> m.isMemberOn(bucketTo)).count();
            points.add(new ActivityPoint(start, active.size(), newMembers, total,
                total == 0 ? null : active.size() / (double) total));
        });
        return points;
    }

    /** 截至 to 的 DAU、WAU、MAU、黏性和成员总数。 */
    public static ActivitySummary summary(List<Member> members, List<ActiveDay> activeDays, LocalDate to) {
        Map<LocalDate, Set<Long>> byDay = new HashMap<>();
        for (ActiveDay day : activeDays) {
            byDay.computeIfAbsent(day.day(), k -> new HashSet<>()).add(day.employeeId());
        }
        long dau = byDay.getOrDefault(to, Set.of()).size();
        long wau = distinct(byDay, to, WAU_DAYS);
        long mau = distinct(byDay, to, MAU_DAYS);
        long dauSum = 0;
        for (int i = 0; i < MAU_DAYS; i++) {
            dauSum += byDay.getOrDefault(to.minusDays(i), Set.of()).size();
        }
        Double stickiness = mau == 0 ? null : dauSum / (double) MAU_DAYS / mau;
        return new ActivitySummary(dau, wau, mau, stickiness, members.stream().filter(m -> m.isMemberOn(to)).count());
    }

    private static long distinct(Map<LocalDate, Set<Long>> byDay, LocalDate to, int days) {
        Set<Long> users = new HashSet<>();
        for (int i = 0; i < days; i++) {
            users.addAll(byDay.getOrDefault(to.minusDays(i), Set.of()));
        }
        return users.size();
    }
}
