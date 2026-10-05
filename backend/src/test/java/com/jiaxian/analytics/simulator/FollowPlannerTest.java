package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.Employee;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FollowPlannerTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 1);

    private static List<EmployeeProfile> profiles(int count) {
        List<EmployeeProfile> profiles = new ArrayList<>();
        for (int rank = 0; rank < count; rank++) {
            Employee employee = Employee.builder().id((long) rank + 1).dept("领域" + rank % 5)
                .communityJoinDate(START.minusDays(10)).build();
            profiles.add(new EmployeeProfile(employee, BloggerPersona.RANDOM, RetentionArchetype.REGULAR, false,
                1.0 / Math.pow(rank + 1, 0.7), START, 60));
        }
        return profiles;
    }

    private static Map<Long, Double> weights(int accounts) {
        Map<Long, Double> weights = new HashMap<>();
        for (int rank = 0; rank < accounts; rank++) {
            weights.put(1000L + rank, 1.0 / Math.pow(rank + 1, 0.8));
        }
        return weights;
    }

    private static List<Long> ids(int accounts) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < accounts; i++) {
            ids.add(1000L + i);
        }
        return ids;
    }

    @Test
    void peopleFollowAboutSevenAccountsEachAndMostActiveOnesFollowMore() {
        List<EmployeeProfile> profiles = profiles(1000);

        List<FollowPlanner.Edge> edges = FollowPlanner.plan(profiles, ids(100), Map.of(), weights(100), START, END, new Random(1));

        Map<Long, Long> perFollower = new HashMap<>();
        edges.forEach(e -> perFollower.merge(e.followerId(), 1L, Long::sum));
        assertThat(edges.size() / 1000.0).isBetween(5.0, 10.0);
        assertThat(perFollower.get(1L)).isGreaterThan(perFollower.get(1000L));
        assertThat(perFollower.values()).allMatch(n -> n >= 4);
    }

    @Test
    void followersAreLongTailedAcrossAccountsAndNeverDuplicatedPerPerson() {
        List<EmployeeProfile> profiles = profiles(1000);

        List<FollowPlanner.Edge> edges = FollowPlanner.plan(profiles, ids(100), Map.of(), weights(100), START, END, new Random(2));

        Map<Long, Long> perAccount = new HashMap<>();
        edges.forEach(e -> perAccount.merge(e.accountId(), 1L, Long::sum));
        List<Long> sorted = perAccount.values().stream().sorted(java.util.Comparator.reverseOrder()).toList();
        assertThat(sorted.get(0)).isGreaterThanOrEqualTo(sorted.get(sorted.size() / 2) * 5);
        long distinctPairs = edges.stream().map(e -> e.followerId() + ":" + e.accountId()).distinct().count();
        assertThat(distinctPairs).isEqualTo(edges.size());
    }

    @Test
    void followsStartAfterTheMemberJoinedAndPreferTheirOwnDomain() {
        List<EmployeeProfile> profiles = profiles(200);
        Map<Long, Set<String>> accountDepts = new HashMap<>();
        for (int i = 0; i < 50; i++) {
            accountDepts.put(1000L + i, Set.of("领域" + i % 5));
        }

        List<FollowPlanner.Edge> edges = FollowPlanner.plan(profiles, ids(50), accountDepts, weights(50), START, END, new Random(3));

        Map<Long, String> deptOf = new HashMap<>();
        profiles.forEach(p -> deptOf.put(p.employee().getId(), p.employee().getDept()));
        long sameDomain = edges.stream().filter(e -> accountDepts.get(e.accountId()).contains(deptOf.get(e.followerId()))).count();
        // 同领域的账号只有 10 个，关注得多的人会把它们关注完，剩下的只能关注别的领域，所以比 70% 略低
        assertThat(sameDomain / (double) edges.size()).isBetween(0.55, 0.9);
        assertThat(edges).allMatch(e -> !e.followTime().toLocalDate().isBefore(START));
    }

    @Test
    void aboutTwelvePercentUnfollowAndNoAccountsMeansNoEdges() {
        List<FollowPlanner.Edge> edges = FollowPlanner.plan(profiles(500), ids(100), Map.of(), weights(100), START, END, new Random(4));

        long unfollowed = edges.stream().filter(e -> e.unfollowTime() != null).count();
        assertThat(unfollowed / (double) edges.size()).isBetween(0.04, 0.14);
        assertThat(FollowPlanner.plan(profiles(10), List.of(), Map.of(), Map.of(), START, END, new Random(5))).isEmpty();
    }

    @Test
    void accountsWithoutAWeightCountAsOneAndFewAccountsCapTheFollowCount() {
        List<FollowPlanner.Edge> edges = FollowPlanner.plan(profiles(20), ids(3), Map.of(), Map.of(), START, END, new Random(6));

        Map<Long, Long> perFollower = new HashMap<>();
        edges.forEach(e -> perFollower.merge(e.followerId(), 1L, Long::sum));
        assertThat(perFollower.values()).allMatch(n -> n <= 3);
        assertThat(perFollower).hasSize(20);
    }
}
