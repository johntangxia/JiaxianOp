package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.domain.Comment;
import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.ContentType;
import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.simulator.ImpressionPlanner;
import com.jiaxian.analytics.simulator.SourceKind;
import com.jiaxian.analytics.simulator.SourceSampler;
import com.jiaxian.analytics.simulator.ViewDetailSampler;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        return planBatch(eventCount, batchId, occurTime, employees, contents, commentsByContentId, Map.of());
    }

    /**
     * 返回 eventCount 个行为事件，加上入口来源的浏览带出的若干未点击曝光事件（不计入 eventCount）。
     * compositeChannelIds 为空时不给浏览带来源信息（旧的行为）。关注入口需要关注关系，实时侧没有，会退回推荐入口。
     */
    public List<RealtimeEvent> planBatch(int eventCount, String batchId, LocalDateTime occurTime,
                                         List<Employee> employees, List<Content> contents,
                                         Function<Long, List<Comment>> commentsByContentId,
                                         Map<String, Long> compositeChannelIds) {
        // 还没加入社区的员工不会产生任何行为；加入日期为空的旧数据视为一直在社区里。
        List<Employee> joined = employees.stream().filter(e -> hasJoined(e, occurTime)).toList();
        List<RealtimeEvent> events = new ArrayList<>(eventCount);
        if (joined.isEmpty()) {
            return events;
        }
        for (int i = 0; i < eventCount; i++) {
            RealtimeEvent event = planOne(batchId, occurTime, joined, contents, commentsByContentId);
            events.add(event);
            if (!compositeChannelIds.isEmpty() && event.getEventType() == RealtimeEventType.VIEW) {
                events.addAll(attachSource(event, contents, joined, compositeChannelIds));
            }
        }
        return events;
    }

    /** 给 VIEW 事件抽来源和停留；入口来源再带出若干由其他员工产生的未点击曝光。 */
    private List<RealtimeEvent> attachSource(RealtimeEvent view, List<Content> contents, List<Employee> joined,
                                             Map<String, Long> compositeChannelIds) {
        Content content = contents.stream().filter(c -> c.getId().equals(view.getContentId())).findFirst().orElseThrow();
        ContentType type = content.getType();
        SourceKind kind = SourceSampler.applyConstraints(SourceSampler.draw(random), type, false);
        Long contentChannelId = content.getChannelId();
        Long entryChannelId = contentChannelId == null && kind == SourceKind.OWN_ENTRY ? null
            : SourceSampler.entryChannelId(kind, contentChannelId == null ? 0L : contentChannelId, compositeChannelIds);
        ViewDetailSampler.Dwell dwell = ViewDetailSampler.sample(type, random);
        view.setSourceType(kind.viewSource());
        view.setEntryChannelId(entryChannelId);
        view.setDwellSeconds(dwell.seconds());
        view.setCompleted(dwell.completed());

        List<RealtimeEvent> impressions = new ArrayList<>();
        if (kind.isEntry() && entryChannelId != null) {
            int unclicked = ImpressionPlanner.unclickedImpressions(kind, random);
            for (int i = 0; i < unclicked; i++) {
                impressions.add(RealtimeEvent.builder()
                    .eventType(RealtimeEventType.IMPRESSION)
                    .contentId(content.getId())
                    .entryChannelId(entryChannelId)
                    .actorEmployeeId(joined.get(random.nextInt(joined.size())).getId())
                    .occurTime(view.getOccurTime())
                    .batchId(view.getBatchId())
                    .build());
            }
        }
        return impressions;
    }

    private static boolean hasJoined(Employee employee, LocalDateTime at) {
        return employee.getCommunityJoinDate() == null || !employee.getCommunityJoinDate().isAfter(at.toLocalDate());
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
