package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.domain.Comment;
import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.Employee;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

public class RealtimeEventPlanner {

    private static final double VIEW_THRESHOLD = 0.60;
    private static final double LIKE_THRESHOLD = 0.72;
    private static final double SHARE_THRESHOLD = 0.76;
    private static final double FAVORITE_THRESHOLD = 0.79;
    private static final double COMMENT_THRESHOLD = 0.89;
    private static final double COMMENT_LIKE_THRESHOLD = 0.95;
    private static final double REPLY_PROBABILITY = 0.3;

    private final Random random;

    public RealtimeEventPlanner(Random random) {
        this.random = random;
    }

    public List<RealtimeEvent> planBatch(int eventCount, String batchId, LocalDateTime occurTime,
                                         List<Employee> employees, List<Content> contents,
                                         Function<Long, List<Comment>> commentsByContentId) {
        List<RealtimeEvent> events = new ArrayList<>(eventCount);
        for (int i = 0; i < eventCount; i++) {
            events.add(planOne(batchId, occurTime, employees, contents, commentsByContentId));
        }
        return events;
    }

    private RealtimeEvent planOne(String batchId, LocalDateTime occurTime, List<Employee> employees,
                                  List<Content> contents, Function<Long, List<Comment>> commentsByContentId) {
        Employee actor = employees.get(random.nextInt(employees.size()));
        double roll = random.nextDouble();

        if (roll >= COMMENT_LIKE_THRESHOLD) {
            return RealtimeEvent.builder()
                .eventType(RealtimeEventType.LOGIN)
                .actorEmployeeId(actor.getId())
                .occurTime(occurTime)
                .batchId(batchId)
                .build();
        }

        Content content = pickContentByPopularity(contents);
        if (roll < VIEW_THRESHOLD) {
            return contentEvent(RealtimeEventType.VIEW, content, actor, batchId, occurTime);
        }
        if (roll < LIKE_THRESHOLD) {
            return contentEvent(RealtimeEventType.LIKE, content, actor, batchId, occurTime);
        }
        if (roll < SHARE_THRESHOLD) {
            return contentEvent(RealtimeEventType.SHARE, content, actor, batchId, occurTime);
        }
        if (roll < FAVORITE_THRESHOLD) {
            return contentEvent(RealtimeEventType.FAVORITE, content, actor, batchId, occurTime);
        }

        List<Comment> comments = commentsByContentId.apply(content.getId());
        if (roll < COMMENT_THRESHOLD) {
            return planComment(content, actor, comments, batchId, occurTime);
        }
        return planCommentLike(content, actor, comments, batchId, occurTime);
    }

    private RealtimeEvent contentEvent(RealtimeEventType type, Content content, Employee actor,
                                       String batchId, LocalDateTime occurTime) {
        return RealtimeEvent.builder()
            .eventType(type)
            .contentId(content.getId())
            .actorEmployeeId(actor.getId())
            .occurTime(occurTime)
            .batchId(batchId)
            .build();
    }

    private RealtimeEvent planComment(Content content, Employee actor, List<Comment> comments,
                                      String batchId, LocalDateTime occurTime) {
        List<Comment> topLevel = comments.stream().filter(c -> c.getParentCommentId() == null).toList();
        Long parentCommentId = null;
        if (!topLevel.isEmpty() && random.nextDouble() < REPLY_PROBABILITY) {
            parentCommentId = topLevel.get(random.nextInt(topLevel.size())).getId();
        }
        return RealtimeEvent.builder()
            .eventType(RealtimeEventType.COMMENT)
            .contentId(content.getId())
            .parentCommentId(parentCommentId)
            .actorEmployeeId(actor.getId())
            .occurTime(occurTime)
            .batchId(batchId)
            .build();
    }

    private RealtimeEvent planCommentLike(Content content, Employee actor, List<Comment> comments,
                                          String batchId, LocalDateTime occurTime) {
        if (comments.isEmpty()) {
            return planComment(content, actor, comments, batchId, occurTime);
        }
        Comment target = comments.get(random.nextInt(comments.size()));
        return RealtimeEvent.builder()
            .eventType(RealtimeEventType.COMMENT_LIKE)
            .contentId(content.getId())
            .targetCommentId(target.getId())
            .actorEmployeeId(actor.getId())
            .occurTime(occurTime)
            .batchId(batchId)
            .build();
    }

    private Content pickContentByPopularity(List<Content> contents) {
        double total = contents.stream().mapToDouble(Content::getPopularityWeight).sum();
        double target = random.nextDouble() * total;
        double cumulative = 0;
        for (Content content : contents) {
            cumulative += content.getPopularityWeight();
            if (target < cumulative) {
                return content;
            }
        }
        return contents.get(contents.size() - 1);
    }
}
