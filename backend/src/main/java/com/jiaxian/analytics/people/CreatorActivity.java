package com.jiaxian.analytics.people;

import com.jiaxian.analytics.pv.Granularity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 纯逻辑：创作者的周期序列和窗口对比。firstPost 是每个员工历史上第一次发帖的日期（从未发帖的不在 map 里）。 */
public final class CreatorActivity {

    private CreatorActivity() {
    }

    /** 区间 [from, to] 内每个周期一个点（补零）；周期两端按区间裁剪。 */
    public static List<CreatorPoint> series(List<PostDay> postDays, Map<Long, LocalDate> firstPost, LocalDate from, LocalDate to,
                                            Granularity granularity) {
        Map<LocalDate, Set<Long>> creators = new TreeMap<>();
        Map<LocalDate, long[]> posts = new TreeMap<>();
        for (LocalDate start = granularity.periodStart(from); !start.isAfter(to); start = granularity.nextPeriodStart(start)) {
            creators.put(start, new HashSet<>());
            posts.put(start, new long[1]);
        }
        for (PostDay day : postDays) {
            if (!day.day().isBefore(from) && !day.day().isAfter(to)) {
                LocalDate bucket = granularity.periodStart(day.day());
                creators.get(bucket).add(day.employeeId());
                posts.get(bucket)[0] += day.posts();
            }
        }
        List<ActivityBucket> buckets = new ArrayList<>();
        creators.forEach((start, set) -> buckets.add(new ActivityBucket(start, set, posts.get(start)[0])));

        List<CreatorPoint> points = new ArrayList<>();
        for (ActivityBucket bucket : buckets) {
            LocalDate bucketFrom = bucket.start().isBefore(from) ? from : bucket.start();
            LocalDate end = granularity.periodEnd(bucket.start());
            LocalDate bucketTo = end.isAfter(to) ? to : end;
            long created = firstPost.values().stream().filter(d -> !d.isBefore(bucketFrom) && !d.isAfter(bucketTo)).count();
            long active = bucket.creators().size();
            points.add(new CreatorPoint(bucket.start(), active, created, bucket.posts(),
                active == 0 ? null : bucket.posts() / (double) active));
        }
        return points;
    }

    private record ActivityBucket(LocalDate start, Set<Long> creators, long posts) {
    }

    /** 窗口 [from, to] 对比上一个等长窗口 [previousFrom, previousTo]；members 是 to 当天的成员数。 */
    public static CreatorSummary summary(List<PostDay> postDays, Map<Long, LocalDate> firstPost, LocalDate from, LocalDate to,
                                         LocalDate previousFrom, LocalDate previousTo, long members) {
        Set<Long> current = new HashSet<>();
        Set<Long> previous = new HashSet<>();
        long currentPosts = 0;
        long previousPosts = 0;
        for (PostDay day : postDays) {
            if (!day.day().isBefore(from) && !day.day().isAfter(to)) {
                current.add(day.employeeId());
                currentPosts += day.posts();
            } else if (!day.day().isBefore(previousFrom) && !day.day().isAfter(previousTo)) {
                previous.add(day.employeeId());
                previousPosts += day.posts();
            }
        }
        long created = firstPost.values().stream().filter(d -> !d.isBefore(from) && !d.isAfter(to)).count();
        long lost = previous.stream().filter(id -> !current.contains(id)).count();
        long returning = current.stream().filter(id -> !previous.contains(id)
            && firstPost.containsKey(id) && firstPost.get(id).isBefore(previousFrom)).count();
        return new CreatorSummary(current.size(), created, lost, returning,
            members == 0 ? null : current.size() / (double) members, currentPosts, previous.size(), previousPosts);
    }
}
