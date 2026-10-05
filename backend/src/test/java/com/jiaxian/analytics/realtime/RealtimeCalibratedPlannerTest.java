package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.domain.Comment;
import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.ContentType;
import com.jiaxian.analytics.domain.Employee;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeCalibratedPlannerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 12, 0);
    private static final Function<Long, List<Comment>> NO_COMMENTS = id -> List.of();

    private static Employee employee(long id, String dept) {
        return Employee.builder().id(id).dept(dept).communityJoinDate(LocalDate.of(2020, 1, 1)).build();
    }

    private static Content content(long id, long channelId, double weight, LocalDateTime publish) {
        return Content.builder().id(id).channelId(channelId).type(ContentType.ARTICLE).popularityWeight(weight)
            .publishTime(publish).build();
    }

    private static RealtimeAudience audience(Map<Long, Double> weights) {
        return new RealtimeAudience(weights, Map.of(1L, "甲", 2L, "乙", 3L, "丙"));
    }

    private static Map<RealtimeEventType, Integer> counts(List<RealtimeEvent> events) {
        Map<RealtimeEventType, Integer> counts = new EnumMap<>(RealtimeEventType.class);
        events.forEach(e -> counts.merge(e.getEventType(), 1, Integer::sum));
        return counts;
    }

    private List<RealtimeEvent> plan(int count, List<Employee> employees, List<Content> contents, RealtimeAudience audience,
                                     long seed) {
        return new RealtimeEventPlanner(new Random(seed)).planCalibratedBatch(count, "calibrated", NOW, employees, contents,
            NO_COMMENTS, Map.of(), audience);
    }

    private static List<Employee> employees(int count) {
        List<Employee> employees = new ArrayList<>();
        for (long id = 1; id <= count; id++) {
            employees.add(employee(id, "甲"));
        }
        return employees;
    }

    @Test
    void theEventMixMatchesTheBackfillModelAboutTwoViewsPerLoginAndAFewPercentInteractions() {
        List<Content> contents = List.of(content(1, 1, 0.5, NOW.minusDays(1)), content(2, 2, 0.5, NOW.minusDays(2)));

        List<RealtimeEvent> events = plan(60000, employees(20), contents, audience(Map.of()), 7);
        Map<RealtimeEventType, Integer> counts = counts(events);
        double views = counts.get(RealtimeEventType.VIEW);

        assertThat(views / counts.get(RealtimeEventType.LOGIN)).isBetween(1.85, 2.15);
        double interactions = counts.getOrDefault(RealtimeEventType.LIKE, 0) + counts.getOrDefault(RealtimeEventType.SHARE, 0)
            + counts.getOrDefault(RealtimeEventType.FAVORITE, 0) + counts.getOrDefault(RealtimeEventType.COMMENT, 0);
        assertThat(interactions / views).as("互动 / 浏览").isBetween(0.025, 0.045);
        assertThat(counts.get(RealtimeEventType.LIKE)).isGreaterThan(counts.get(RealtimeEventType.SHARE));
    }

    @Test
    void producesExactlyTheRequestedBehaviourEventsStampedWithBatchAndTime() {
        List<RealtimeEvent> events = plan(500, employees(5), List.of(content(1, 1, 0.5, NOW.minusDays(1))), audience(Map.of()), 3);

        assertThat(events).hasSize(500);
        assertThat(events).allMatch(e -> "calibrated".equals(e.getBatchId()) && NOW.equals(e.getOccurTime()));
        assertThat(events.stream().filter(e -> e.getEventType() == RealtimeEventType.LOGIN))
            .allMatch(e -> e.getContentId() == null);
        assertThat(events.stream().filter(e -> e.getEventType() != RealtimeEventType.LOGIN))
            .allMatch(e -> e.getContentId() != null);
    }

    @Test
    void actorsAreChosenByRecentLoginWeightAndPeopleWithoutWeightNeverAct() {
        Map<Long, Double> weights = new HashMap<>();
        weights.put(1L, 90.0);
        weights.put(2L, 10.0);   // 员工 3–5 没有任何近期登录：淡出的人

        List<RealtimeEvent> events = plan(20000, employees(5), List.of(content(1, 1, 0.5, NOW.minusDays(1))), audience(weights), 11);
        Map<Long, Integer> byActor = new HashMap<>();
        events.forEach(e -> byActor.merge(e.getActorEmployeeId(), 1, Integer::sum));

        assertThat(byActor.keySet()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(byActor.get(1L) / 20000.0).isBetween(0.87, 0.93);
    }

    @Test
    void withoutAnyWeightEveryJoinedEmployeeActsAboutEquallyOften() {
        List<RealtimeEvent> events = plan(20000, employees(4), List.of(content(1, 1, 0.5, NOW.minusDays(1))), audience(Map.of()), 13);
        Map<Long, Integer> byActor = new HashMap<>();
        events.forEach(e -> byActor.merge(e.getActorEmployeeId(), 1, Integer::sum));

        assertThat(byActor).hasSize(4);
        assertThat(byActor.values()).allMatch(n -> n > 4000 && n < 6000);
    }

    @Test
    void hotterAndNewerContentGetsMoreOfTheEvents() {
        List<Content> contents = List.of(
            content(1, 3, 1.0, NOW.minusDays(2)),
            content(2, 3, 0.01, NOW.minusDays(2)),
            content(3, 3, 1.0, NOW.minusDays(25)));

        List<RealtimeEvent> events = plan(30000, List.of(employee(1, "丁")), contents, audience(Map.of()), 17);
        Map<Long, Integer> byContent = new HashMap<>();
        events.stream().filter(e -> e.getContentId() != null).forEach(e -> byContent.merge(e.getContentId(), 1, Integer::sum));

        assertThat(byContent.get(1L)).isGreaterThan(byContent.get(2L) * 3);
        assertThat(byContent.get(1L)).isGreaterThan(byContent.getOrDefault(3L, 0) * 20);
    }

    @Test
    void mostViewsComeFromTheActorsOwnDomainWhenThereIsContentThere() {
        List<Content> contents = new ArrayList<>();
        for (long id = 1; id <= 30; id++) {
            contents.add(content(id, id % 3 + 1, 0.5, NOW.minusDays(1)));
        }

        List<RealtimeEvent> events = plan(30000, List.of(employee(1, "甲")), contents, audience(Map.of()), 19);
        Map<Long, Long> channelOf = new HashMap<>();
        contents.forEach(c -> channelOf.put(c.getId(), c.getChannelId()));
        long views = events.stream().filter(e -> e.getEventType() == RealtimeEventType.VIEW).count();
        long own = events.stream().filter(e -> e.getEventType() == RealtimeEventType.VIEW)
            .filter(e -> channelOf.get(e.getContentId()) == 1L).count();

        // 70% 先限定本领域，其余 30% 在全体里挑（其中三分之一恰好也是本领域）：期望约 0.8
        assertThat(own / (double) views).isBetween(0.74, 0.86);
    }

    @Test
    void employeesWhoHaveNotJoinedNeverActAndNoContentOrNobodyMeansNoEvents() {
        Employee future = Employee.builder().id(9L).dept("甲").communityJoinDate(NOW.toLocalDate().plusDays(1)).build();
        List<Content> contents = List.of(content(1, 1, 0.5, NOW.minusDays(1)));

        assertThat(plan(100, List.of(future), contents, audience(Map.of()), 1)).isEmpty();
        assertThat(plan(100, employees(3), List.of(), audience(Map.of()), 1)).isEmpty();
        assertThat(plan(1000, List.of(employee(1, "甲"), future), contents, audience(Map.of()), 1))
            .allMatch(e -> e.getActorEmployeeId() == 1L);
    }

    @Test
    void repliesAndCommentLikesTargetExistingCommentsAndTheSameSeedGivesTheSameEvents() {
        List<Content> contents = List.of(content(1, 1, 0.5, NOW.minusDays(1)));
        Function<Long, List<Comment>> comments = id -> List.of(Comment.builder().id(50L).build());

        List<RealtimeEvent> first = new RealtimeEventPlanner(new Random(5)).planCalibratedBatch(50000, "b", NOW, employees(5),
            contents, comments, Map.of(), audience(Map.of()));
        List<RealtimeEvent> second = new RealtimeEventPlanner(new Random(5)).planCalibratedBatch(50000, "b", NOW, employees(5),
            contents, comments, Map.of(), audience(Map.of()));

        assertThat(first).usingRecursiveComparison().isEqualTo(second);
        assertThat(first.stream().filter(e -> e.getEventType() == RealtimeEventType.COMMENT_LIKE))
            .isNotEmpty().allMatch(e -> e.getTargetCommentId() == 50L);
        assertThat(first.stream().filter(e -> e.getEventType() == RealtimeEventType.COMMENT && e.getParentCommentId() != null))
            .allMatch(e -> e.getParentCommentId() == 50L);
    }

    @Test
    void viewsCarrySourceAndEntryViewsBringUnclickedImpressions() {
        List<Content> contents = List.of(content(1, 1, 0.5, NOW.minusDays(1)));
        Map<String, Long> composite = Map.of("推荐", 901L, "热点", 902L, "视频", 903L, "关注", 904L);

        List<RealtimeEvent> events = new RealtimeEventPlanner(new Random(9)).planCalibratedBatch(5000, "b", NOW, employees(5),
            contents, NO_COMMENTS, composite, audience(Map.of()));

        assertThat(events.stream().filter(e -> e.getEventType() == RealtimeEventType.VIEW))
            .isNotEmpty().allMatch(e -> e.getSourceType() != null && e.getDwellSeconds() != null);
        assertThat(events.stream().filter(e -> e.getEventType() == RealtimeEventType.IMPRESSION)).isNotEmpty();
    }
}
