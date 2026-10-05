package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.domain.Comment;
import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.ContentType;
import com.jiaxian.analytics.domain.ViewSource;
import com.jiaxian.analytics.domain.Employee;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeEventPlannerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 4, 12, 0);
    private static final String BATCH = "planner-test";

    private static List<Employee> employees(int count) {
        List<Employee> employees = new ArrayList<>();
        for (long id = 1; id <= count; id++) {
            employees.add(Employee.builder().id(id).build());
        }
        return employees;
    }

    private static Content content(long id, double popularityWeight) {
        return Content.builder().id(id).popularityWeight(popularityWeight).build();
    }

    private static Comment comment(long id, Long parentCommentId) {
        return Comment.builder().id(id).parentCommentId(parentCommentId).build();
    }

    private static final Function<Long, List<Comment>> NO_COMMENTS = contentId -> List.of();

    private List<RealtimeEvent> plan(RealtimeEventPlanner planner, int count, List<Content> contents,
                                     Function<Long, List<Comment>> comments) {
        return planner.planBatch(count, BATCH, NOW, employees(5), contents, comments);
    }

    @Test
    void producesRequestedNumberOfEventsStampedWithBatchAndTime() {
        RealtimeEventPlanner planner = new RealtimeEventPlanner(new Random(42));

        List<RealtimeEvent> events = plan(planner, 25, List.of(content(1, 0.5)), NO_COMMENTS);

        assertThat(events).hasSize(25);
        assertThat(events).allMatch(e -> BATCH.equals(e.getBatchId()) && NOW.equals(e.getOccurTime()));
        assertThat(events).allMatch(e -> e.getActorEmployeeId() >= 1 && e.getActorEmployeeId() <= 5);
    }

    @Test
    void loginEventsCarryNoContentAndAllOtherEventsDo() {
        RealtimeEventPlanner planner = new RealtimeEventPlanner(new Random(42));

        List<RealtimeEvent> events = plan(planner, 2000, List.of(content(1, 0.5)), NO_COMMENTS);

        assertThat(events).filteredOn(e -> e.getEventType() == RealtimeEventType.LOGIN)
            .isNotEmpty()
            .allMatch(e -> e.getContentId() == null);
        assertThat(events).filteredOn(e -> e.getEventType() != RealtimeEventType.LOGIN)
            .allMatch(e -> e.getContentId() != null);
    }

    @Test
    void popularContentIsPickedFarMoreOftenThanUnpopularContent() {
        RealtimeEventPlanner planner = new RealtimeEventPlanner(new Random(42));

        List<RealtimeEvent> events = plan(planner, 2000,
            List.of(content(1, 0.9), content(2, 0.1)), NO_COMMENTS);

        long hot = events.stream().filter(e -> Long.valueOf(1).equals(e.getContentId())).count();
        long cold = events.stream().filter(e -> Long.valueOf(2).equals(e.getContentId())).count();
        assertThat(hot).isGreaterThan(cold * 3);
    }

    @Test
    void withoutExistingCommentsThereAreNoRepliesAndNoCommentLikes() {
        RealtimeEventPlanner planner = new RealtimeEventPlanner(new Random(42));

        List<RealtimeEvent> events = plan(planner, 2000, List.of(content(1, 0.5)), NO_COMMENTS);

        assertThat(events).noneMatch(e -> e.getEventType() == RealtimeEventType.COMMENT_LIKE);
        assertThat(events).filteredOn(e -> e.getEventType() == RealtimeEventType.COMMENT)
            .isNotEmpty()
            .allMatch(e -> e.getParentCommentId() == null);
    }

    @Test
    void repliesTargetOnlyTopLevelCommentsAndCommentLikesTargetExistingComments() {
        RealtimeEventPlanner planner = new RealtimeEventPlanner(new Random(42));
        List<Comment> existing = List.of(comment(1, null), comment(2, null), comment(3, 1L));

        List<RealtimeEvent> events = plan(planner, 2000, List.of(content(1, 0.5)), contentId -> existing);

        assertThat(events)
            .filteredOn(e -> e.getEventType() == RealtimeEventType.COMMENT && e.getParentCommentId() != null)
            .isNotEmpty()
            .extracting(RealtimeEvent::getParentCommentId)
            .containsOnly(1L, 2L);
        assertThat(events).filteredOn(e -> e.getEventType() == RealtimeEventType.COMMENT_LIKE)
            .isNotEmpty()
            .allMatch(e -> e.getContentId() != null)
            .extracting(RealtimeEvent::getTargetCommentId)
            .containsOnly(1L, 2L, 3L);
    }

    @Test
    void allSevenBehaviourEventTypesAppearWhenCommentsExist() {
        RealtimeEventPlanner planner = new RealtimeEventPlanner(new Random(42));
        List<Comment> existing = List.of(comment(1, null));

        List<RealtimeEvent> events = plan(planner, 2000, List.of(content(1, 0.5)), contentId -> existing);

        // IMPRESSION 只在提供综合频道（实时来源模拟）时才会出现，见下面的来源测试
        assertThat(events).extracting(RealtimeEvent::getEventType)
            .contains(RealtimeEventType.VIEW, RealtimeEventType.LIKE, RealtimeEventType.SHARE,
                RealtimeEventType.FAVORITE, RealtimeEventType.COMMENT, RealtimeEventType.COMMENT_LIKE,
                RealtimeEventType.LOGIN)
            .doesNotContain(RealtimeEventType.IMPRESSION);
    }

    @Test
    void sameSeedProducesSameEvents() {
        List<Content> contents = List.of(content(1, 0.6), content(2, 0.4));

        List<RealtimeEvent> first = plan(new RealtimeEventPlanner(new Random(5)), 50, contents, NO_COMMENTS);
        List<RealtimeEvent> second = plan(new RealtimeEventPlanner(new Random(5)), 50, contents, NO_COMMENTS);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void employeesWhoHaveNotJoinedTheCommunityNeverAct() {
        RealtimeEventPlanner planner = new RealtimeEventPlanner(new Random(42));
        List<Employee> employees = List.of(
            Employee.builder().id(1L).communityJoinDate(NOW.toLocalDate().minusDays(30)).build(),
            Employee.builder().id(2L).communityJoinDate(NOW.toLocalDate()).build(),
            Employee.builder().id(3L).communityJoinDate(NOW.toLocalDate().plusDays(1)).build(),
            Employee.builder().id(4L).build());

        List<RealtimeEvent> events = planner.planBatch(2000, BATCH, NOW, employees, List.of(content(1, 0.5)), NO_COMMENTS);

        assertThat(events).extracting(RealtimeEvent::getActorEmployeeId).doesNotContain(3L);
        assertThat(events).extracting(RealtimeEvent::getActorEmployeeId).contains(1L, 2L, 4L);
    }

    @Test
    void returnsNoEventsWhenNobodyHasJoinedYet() {
        RealtimeEventPlanner planner = new RealtimeEventPlanner(new Random(42));
        List<Employee> employees = List.of(
            Employee.builder().id(1L).communityJoinDate(NOW.toLocalDate().plusDays(5)).build());

        assertThat(planner.planBatch(50, BATCH, NOW, employees, List.of(content(1, 0.5)), NO_COMMENTS)).isEmpty();
    }

    private static final java.util.Map<String, Long> COMPOSITE = java.util.Map.of("推荐", 2L, "热点", 3L, "视频", 4L, "关注", 1L);

    private List<RealtimeEvent> planWithSources(int count, long seed) {
        Content video = Content.builder().id(1L).channelId(50L).type(ContentType.VIDEO).popularityWeight(0.5).build();
        Content article = Content.builder().id(2L).channelId(51L).type(ContentType.ARTICLE).popularityWeight(0.5).build();
        return new RealtimeEventPlanner(new Random(seed)).planBatch(count, BATCH, NOW, employees(5),
            List.of(video, article), NO_COMMENTS, COMPOSITE);
    }

    @Test
    void withCompositeChannelsEveryViewCarriesASourceAndDwell() {
        List<RealtimeEvent> views = planWithSources(3000, 7).stream()
            .filter(e -> e.getEventType() == RealtimeEventType.VIEW).toList();

        assertThat(views).isNotEmpty().allMatch(v -> v.getSourceType() != null
            && v.getDwellSeconds() != null && v.getDwellSeconds() >= 3 && v.getCompleted() != null);
        assertThat(views).filteredOn(v -> v.getSourceType() == ViewSource.ENTRY)
            .allMatch(v -> v.getEntryChannelId() != null);
        assertThat(views).filteredOn(v -> v.getSourceType() != ViewSource.ENTRY)
            .allMatch(v -> v.getEntryChannelId() == null);
        // 实时侧没有关注关系：关注入口（频道 1）不会出现；文章不会走视频入口（频道 4）
        assertThat(views).extracting(RealtimeEvent::getEntryChannelId).doesNotContain(1L);
        assertThat(views).filteredOn(v -> v.getContentId() == 2L).extracting(RealtimeEvent::getEntryChannelId)
            .doesNotContain(4L);
    }

    @Test
    void entryViewsBringUnclickedImpressionsOnTheSameContentAndEntryWithoutChangingTheBehaviourEventCount() {
        List<RealtimeEvent> events = planWithSources(3000, 8);

        List<RealtimeEvent> behaviour = events.stream().filter(e -> e.getEventType() != RealtimeEventType.IMPRESSION).toList();
        List<RealtimeEvent> impressions = events.stream().filter(e -> e.getEventType() == RealtimeEventType.IMPRESSION).toList();
        assertThat(behaviour).hasSize(3000);
        assertThat(impressions).isNotEmpty().allMatch(i -> i.getEntryChannelId() != null && i.getContentId() != null
            && i.getActorEmployeeId() != null && NOW.equals(i.getOccurTime()) && BATCH.equals(i.getBatchId()));
        assertThat(impressions.size()).isGreaterThan(
            (int) behaviour.stream().filter(e -> e.getSourceType() == ViewSource.ENTRY).count());
    }

    @Test
    void withoutCompositeChannelsViewsStayWithoutSourceLikeBefore() {
        List<RealtimeEvent> events = plan(new RealtimeEventPlanner(new Random(9)), 500, List.of(content(1, 0.5)), NO_COMMENTS);

        assertThat(events).hasSize(500).allMatch(e -> e.getSourceType() == null && e.getEntryChannelId() == null
            && e.getEventType() != RealtimeEventType.IMPRESSION);
    }
}
