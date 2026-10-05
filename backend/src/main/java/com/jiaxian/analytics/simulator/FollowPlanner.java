package com.jiaxian.analytics.simulator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 规划关注关系：员工关注 round(2 + 10 × 活跃倾向) 个账号，70% 偏向与自己同领域的账号；
 * 关注时间在加入社区之后；约 12% 的关注在 30–200 天后取消。只做规划，不落库。
 */
public final class FollowPlanner {

    static final int BASE_FOLLOWS = 2;
    static final double FOLLOWS_PER_PROPENSITY = 10;
    static final double SAME_DOMAIN_RATIO = 0.7;
    static final double UNFOLLOW_RATIO = 0.12;
    static final int MIN_UNFOLLOW_DAYS = 30;
    static final int MAX_UNFOLLOW_DAYS = 200;

    private FollowPlanner() {
    }

    public record Edge(long followerId, long accountId, LocalDateTime followTime, LocalDateTime unfollowTime) {

        public boolean activeAt(LocalDateTime time) {
            return !followTime.isAfter(time) && (unfollowTime == null || unfollowTime.isAfter(time));
        }
    }

    /**
     * @param accountDepts 账号 id → 该账号成员所在的领域集合，用来判断"同领域"
     */
    public static List<Edge> plan(List<EmployeeProfile> profiles, List<Long> accountIds,
                                  Map<Long, Set<String>> accountDepts, LocalDate historyStart, LocalDate historyEnd,
                                  Random random) {
        List<Edge> edges = new ArrayList<>();
        if (accountIds.isEmpty()) {
            return edges;
        }
        for (EmployeeProfile profile : profiles) {
            long wanted = Math.round(BASE_FOLLOWS + FOLLOWS_PER_PROPENSITY * profile.propensity());
            String dept = profile.employee().getDept();
            List<Long> sameDomain = accountIds.stream()
                .filter(id -> accountDepts.getOrDefault(id, Set.of()).contains(dept)).toList();
            LocalDate from = profile.joinDate() != null && profile.joinDate().isAfter(historyStart)
                ? profile.joinDate() : historyStart;

            Set<Long> followed = new HashSet<>();
            for (int i = 0; i < wanted && followed.size() < accountIds.size(); i++) {
                List<Long> pool = !sameDomain.isEmpty() && random.nextDouble() < SAME_DOMAIN_RATIO ? sameDomain : accountIds;
                long accountId = pool.get(random.nextInt(pool.size()));
                if (!followed.add(accountId)) {
                    continue;
                }
                LocalDateTime followTime = ActivityModel.timeOfDay(ActivityModel.workingDay(from, historyEnd, random), random);
                LocalDateTime unfollowTime = null;
                if (random.nextDouble() < UNFOLLOW_RATIO) {
                    LocalDateTime candidate = followTime.plusDays(
                        MIN_UNFOLLOW_DAYS + random.nextInt(MAX_UNFOLLOW_DAYS - MIN_UNFOLLOW_DAYS + 1));
                    if (candidate.toLocalDate().isBefore(historyEnd)) {
                        unfollowTime = candidate;
                    }
                }
                edges.add(new Edge(profile.employee().getId(), accountId, followTime, unfollowTime));
            }
        }
        return edges;
    }
}
