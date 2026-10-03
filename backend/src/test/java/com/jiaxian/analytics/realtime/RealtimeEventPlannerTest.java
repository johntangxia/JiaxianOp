package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.domain.Comment;
import com.jiaxian.analytics.domain.Content;
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
    void allSevenEventTypesAppearWhenCommentsExist() {
        RealtimeEventPlanner planner = new RealtimeEventPlanner(new Random(42));
        List<Comment> existing = List.of(comment(1, null));

        List<RealtimeEvent> events = plan(planner, 2000, List.of(content(1, 0.5)), contentId -> existing);

        assertThat(events).extracting(RealtimeEvent::getEventType)
            .contains(RealtimeEventType.values());
    }

    @Test
    void sameSeedProducesSameEvents() {
        List<Content> contents = List.of(content(1, 0.6), content(2, 0.4));

        List<RealtimeEvent> first = plan(new RealtimeEventPlanner(new Random(5)), 50, contents, NO_COMMENTS);
        List<RealtimeEvent> second = plan(new RealtimeEventPlanner(new Random(5)), 50, contents, NO_COMMENTS);

        assertThat(first).isEqualTo(second);
    }
}
