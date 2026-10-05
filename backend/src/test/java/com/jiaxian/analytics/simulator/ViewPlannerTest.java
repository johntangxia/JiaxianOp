package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.ContentType;
import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.simulator.ViewPlanner.Candidate;
import com.jiaxian.analytics.simulator.ViewPlanner.PlannedView;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ViewPlannerTest {

    private static final LocalDateTime LOGIN = LocalDateTime.of(2025, 6, 10, 9, 30);
    private static final Map<String, Long> COMPOSITE = Map.of("推荐", 901L, "热点", 902L, "视频", 903L, "关注", 904L);

    private static EmployeeProfile viewer(long id, String dept, double propensity) {
        return new EmployeeProfile(Employee.builder().id(id).dept(dept).communityJoinDate(LocalDate.of(2024, 1, 1)).build(),
            BloggerPersona.RANDOM, RetentionArchetype.REGULAR, false, propensity, LocalDate.of(2030, 1, 1), 60);
    }

    private static Candidate content(long id, LocalDateTime publish, String channel, long account, ContentType type, double weight) {
        return new Candidate(id, publish, id % 100 + 1000, channel, account, type, weight);
    }

    private static ViewPlanner planner(List<Candidate> contents, Map<Long, List<FollowPlanner.Edge>> edges) {
        return new ViewPlanner(contents, COMPOSITE, edges);
    }

    // ---- 次数与倍率 ----

    @Test
    void poissonHasTheRequestedMeanAndZeroMeanGivesZero() {
        Random random = new Random(1);
        double total = 0;
        for (int i = 0; i < 20000; i++) {
            total += ViewPlanner.poisson(2.0, random);
        }

        assertThat(total / 20000).isBetween(1.9, 2.1);
        assertThat(ViewPlanner.poisson(0, random)).isZero();
        assertThat(ViewPlanner.poisson(-1, random)).isZero();
    }

    @Test
    void multipliersAverageOneStayInRangeAndGrowWithPropensity() {
        List<EmployeeProfile> profiles = new ArrayList<>();
        for (int rank = 0; rank < 1000; rank++) {
            profiles.add(viewer(rank, "甲", 1.0 / Math.pow(rank + 1, 0.7)));
        }

        double[] multipliers = ViewPlanner.multipliers(profiles);

        assertThat(java.util.Arrays.stream(multipliers).average().orElseThrow()).isBetween(0.999, 1.001);
        assertThat(multipliers[0]).isGreaterThan(multipliers[500]).isGreaterThan(multipliers[999]);
        assertThat(multipliers[999]).isGreaterThan(0.2);
        assertThat(multipliers[0]).isLessThan(5.0);
        assertThat(ViewPlanner.multipliers(List.of())).isEmpty();
    }

    // ---- 时间与候选窗口 ----

    @Test
    void viewsHappenOnTheLoginDayAfterTheLoginAndOnlyOnContentPublishedWithinThirtyDays() {
        List<Candidate> contents = List.of(
            content(1, LOGIN.minusDays(5), "甲", 1, ContentType.ARTICLE, 0.5),
            content(2, LOGIN.minusDays(45), "甲", 1, ContentType.ARTICLE, 0.5),      // 太老
            content(3, LOGIN.plusHours(12), "甲", 1, ContentType.ARTICLE, 0.5));    // 登录时还没发布
        ViewPlanner planner = planner(contents, Map.of());
        Random random = new Random(3);

        List<PlannedView> views = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            views.addAll(planner.planLogin(viewer(7, "甲", 0.5), LOGIN, 1.0, 5.0, random));
        }

        assertThat(views).isNotEmpty();
        assertThat(views).allSatisfy(v -> {
            assertThat(v.time()).isAfterOrEqualTo(LOGIN);
            assertThat(v.time().toLocalDate()).isEqualTo(LOGIN.toLocalDate());
            assertThat(v.viewerId()).isEqualTo(7);
        });
        // 3 号内容 12 小时之后才发布，最晚的浏览是登录后 4 小时，所以只会看到 1 号
        assertThat(views).extracting(PlannedView::contentId).containsOnly(1L);
    }

    @Test
    void aViewLateInTheEveningStaysOnTheSameDay() {
        ViewPlanner planner = planner(List.of(content(1, LOGIN.minusDays(1), "甲", 1, ContentType.ARTICLE, 0.5)), Map.of());
        LocalDateTime lateLogin = LocalDate.of(2025, 6, 10).atTime(23, 50);
        Random random = new Random(5);

        for (int i = 0; i < 200; i++) {
            for (PlannedView view : planner.planLogin(viewer(7, "甲", 0.5), lateLogin, 1.0, 5.0, random)) {
                assertThat(view.time().toLocalDate()).isEqualTo(lateLogin.toLocalDate());
            }
        }
    }

    @Test
    void noCandidatesMeansNoViews() {
        ViewPlanner planner = planner(List.of(), Map.of());

        assertThat(planner.planLogin(viewer(7, "甲", 0.5), LOGIN, 1.0, 10.0, new Random(1))).isEmpty();
    }

    @Test
    void lambdaTimesMultiplierDecidesTheNumberOfViewsPerLogin() {
        ViewPlanner planner = planner(List.of(content(1, LOGIN.minusDays(1), "甲", 1, ContentType.ARTICLE, 0.5)), Map.of());
        Random random = new Random(9);

        int views = 0;
        for (int i = 0; i < 5000; i++) {
            views += planner.planLogin(viewer(7, "甲", 0.5), LOGIN, 1.5, 2.0, random).size();
        }

        assertThat(views / 5000.0).isBetween(2.8, 3.2);
    }

    // ---- 内容选择 ----

    @Test
    void hotterAndNewerContentGetsMoreViews() {
        List<Candidate> contents = List.of(
            content(1, LOGIN.minusDays(3), "甲", 1, ContentType.ARTICLE, 1.0),
            content(2, LOGIN.minusDays(3), "甲", 1, ContentType.ARTICLE, 0.01),
            content(3, LOGIN.minusDays(20), "甲", 1, ContentType.ARTICLE, 1.0));
        ViewPlanner planner = planner(contents, Map.of());
        Random random = new Random(11);
        Map<Long, Integer> counts = new HashMap<>();

        for (int i = 0; i < 4000; i++) {
            planner.planLogin(viewer(7, "甲", 0.5), LOGIN, 1.0, 3.0, random)
                .forEach(v -> counts.merge(v.contentId(), 1, Integer::sum));
        }

        assertThat(counts.get(1L)).isGreaterThan(counts.get(2L) * 3);      // 热度高的被看得多
        assertThat(counts.get(1L)).isGreaterThan(counts.get(3L) * 10);     // 17 天前发布的衰减得厉害
    }

    @Test
    void aboutSeventyPercentOfViewsComeFromTheViewersOwnDomainWhenThereIsChoice() {
        List<Candidate> contents = new ArrayList<>();
        String[] domains = {"甲", "乙", "丙", "丁", "戊"};
        for (int i = 0; i < 50; i++) {
            contents.add(content(i + 1, LOGIN.minusDays(1 + i % 10), domains[i % 5], 1, ContentType.ARTICLE, 0.5));
        }
        ViewPlanner planner = planner(contents, Map.of());
        Random random = new Random(13);
        Map<Long, String> domainOf = new HashMap<>();
        contents.forEach(c -> domainOf.put(c.id(), c.channelName()));

        long own = 0;
        long total = 0;
        for (int i = 0; i < 3000; i++) {
            for (PlannedView view : planner.planLogin(viewer(7, "甲", 0.5), LOGIN, 1.0, 3.0, random)) {
                total++;
                if (domainOf.get(view.contentId()).equals("甲")) {
                    own++;
                }
            }
        }

        // 70% 先限定本领域，其余 30% 在全体里挑（其中 20% 恰好也是本领域）：期望约 0.76
        assertThat(own / (double) total).isBetween(0.70, 0.82);
    }

    // ---- 来源与约束 ----

    @Test
    void sourceSharesFollowTheConfiguredReferenceValues() {
        List<Candidate> contents = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            contents.add(content(i + 1, LOGIN.minusDays(1 + i % 10), i % 2 == 0 ? "甲" : "乙", 1 + i % 5,
                i % 3 == 0 ? ContentType.VIDEO : ContentType.ARTICLE, 0.5));
        }
        // 每个浏览者关注所有账号，这样关注入口不会因为"没有关注者"退回推荐
        Map<Long, List<FollowPlanner.Edge>> edges = new HashMap<>();
        List<FollowPlanner.Edge> all = new ArrayList<>();
        for (long account = 1; account <= 5; account++) {
            all.add(new FollowPlanner.Edge(7, account, LOGIN.minusDays(100), null));
        }
        edges.put(7L, all);
        ViewPlanner planner = planner(contents, edges);
        Random random = new Random(17);
        Map<SourceKind, Integer> counts = new HashMap<>();
        int total = 0;

        for (int i = 0; i < 20000; i++) {
            for (PlannedView view : planner.planLogin(viewer(7, "甲", 0.5), LOGIN, 1.0, 3.0, random)) {
                counts.merge(view.kind(), 1, Integer::sum);
                total++;
            }
        }

        assertThat(counts.get(SourceKind.SHARE) / (double) total).isBetween(0.38, 0.42);
        assertThat(counts.get(SourceKind.OTHER) / (double) total).isBetween(0.31, 0.35);
        assertThat(counts.get(SourceKind.SEARCH) / (double) total).isBetween(0.09, 0.11);
        assertThat(counts.get(SourceKind.OWN_ENTRY) / (double) total).isBetween(0.015, 0.025);
    }

    @Test
    void followEntryOnlyShowsContentOfAccountsTheViewerFollowsAtThatTime() {
        List<Candidate> contents = List.of(
            content(1, LOGIN.minusDays(1), "甲", 10, ContentType.ARTICLE, 0.5),
            content(2, LOGIN.minusDays(1), "甲", 20, ContentType.ARTICLE, 0.5));
        // 关注账号 10；账号 20 关注得太晚（登录之后才关注）
        Map<Long, List<FollowPlanner.Edge>> edges = Map.of(7L, List.of(
            new FollowPlanner.Edge(7, 10, LOGIN.minusDays(30), null),
            new FollowPlanner.Edge(7, 20, LOGIN.plusDays(5), null)));
        ViewPlanner planner = planner(contents, edges);
        Random random = new Random(19);
        List<PlannedView> followViews = new ArrayList<>();

        for (int i = 0; i < 20000; i++) {
            planner.planLogin(viewer(7, "乙", 0.5), LOGIN, 1.0, 3.0, random).stream()
                .filter(v -> v.kind() == SourceKind.FOLLOW_ENTRY).forEach(followViews::add);
        }

        assertThat(followViews).isNotEmpty();
        assertThat(followViews).extracting(PlannedView::contentId).containsOnly(1L);
        assertThat(followViews).allSatisfy(v -> assertThat(v.entryChannelId()).isEqualTo(904L));
    }

    @Test
    void viewersWithoutFollowsNeverGetTheFollowEntryAndVideoEntryNeedsAVideo() {
        List<Candidate> articlesOnly = List.of(content(1, LOGIN.minusDays(1), "甲", 10, ContentType.ARTICLE, 0.5));
        ViewPlanner planner = planner(articlesOnly, Map.of());
        Random random = new Random(23);

        for (int i = 0; i < 5000; i++) {
            for (PlannedView view : planner.planLogin(viewer(7, "甲", 0.5), LOGIN, 1.0, 3.0, random)) {
                assertThat(view.kind()).isNotIn(SourceKind.FOLLOW_ENTRY, SourceKind.VIDEO_ENTRY);
            }
        }
    }

    @Test
    void ownEntryUsesTheContentsOwnChannelAndCompositeEntriesUseTheCompositeChannels() {
        List<Candidate> contents = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            contents.add(content(i + 1, LOGIN.minusDays(1 + i % 7), "甲", 1, ContentType.VIDEO, 0.5));
        }
        ViewPlanner planner = planner(contents, Map.of());
        Random random = new Random(29);
        Map<Long, Long> channelOf = new HashMap<>();
        contents.forEach(c -> channelOf.put(c.id(), c.channelId()));
        Map<SourceKind, Long> composite = Map.of(SourceKind.RECOMMEND_ENTRY, 901L, SourceKind.HOT_ENTRY, 902L, SourceKind.VIDEO_ENTRY, 903L);

        int own = 0;
        for (int i = 0; i < 20000; i++) {
            for (PlannedView view : planner.planLogin(viewer(7, "甲", 0.5), LOGIN, 1.0, 3.0, random)) {
                if (view.kind() == SourceKind.OWN_ENTRY) {
                    own++;
                    assertThat(view.entryChannelId()).isEqualTo(channelOf.get(view.contentId()));
                } else if (composite.containsKey(view.kind())) {
                    assertThat(view.entryChannelId()).isEqualTo(composite.get(view.kind()));
                } else if (!view.kind().isEntry()) {
                    assertThat(view.entryChannelId()).isNull();
                }
            }
        }
        assertThat(own).isPositive();
    }

    @Test
    void theSameSeedGivesTheSameViews() {
        List<Candidate> contents = List.of(content(1, LOGIN.minusDays(1), "甲", 1, ContentType.ARTICLE, 0.5),
            content(2, LOGIN.minusDays(2), "乙", 1, ContentType.VIDEO, 0.7));
        ViewPlanner planner = planner(contents, Map.of());

        assertThat(planner.planLogin(viewer(7, "甲", 0.5), LOGIN, 1.0, 6.0, new Random(31)))
            .isEqualTo(planner.planLogin(viewer(7, "甲", 0.5), LOGIN, 1.0, 6.0, new Random(31)));
    }
}
