package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.domain.Comment;
import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.ContentType;
import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.simulator.ImpressionPlanner;
import com.jiaxian.analytics.simulator.SourceKind;
import com.jiaxian.analytics.simulator.SimulationConfig;
import com.jiaxian.analytics.simulator.SourceSampler;
import com.jiaxian.analytics.simulator.ViewDetailSampler;
import com.jiaxian.analytics.simulator.ViewPlanner;

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

    // ---- 校准后的事件结构（和历史回填同一个模型：浏览由登录产生，互动是浏览之后的少数） ----
    /** 沉默读者约占 35%，不互动；实时侧不知道谁是沉默读者，所以把互动概率按 65% 折算。 */
    static final double INTERACTOR_SHARE = 0.65;
    static final double LIKE_PER_VIEW = 0.03 * INTERACTOR_SHARE;
    static final double SHARE_PER_VIEW = 0.006 * INTERACTOR_SHARE;
    static final double FAVORITE_PER_VIEW = 0.005 * INTERACTOR_SHARE;
    static final double COMMENT_PER_VIEW = 0.005 * INTERACTOR_SHARE;
    static final double COMMENT_LIKE_PER_COMMENT = 0.3;
    /** 每次登录平均浏览的次数（取历史回填的默认值）。 */
    static final double VIEWS_PER_LOGIN = SimulationConfig.DEFAULT_VIEWS_PER_ACTIVE_DAY;
    static final double SAME_DOMAIN_RATIO = 0.7;

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

    /**
     * 校准后的批次：事件结构和历史回填一致（每次登录约 2 次浏览，互动约占浏览的 3%），演员按最近的登录次数加权
     * （常驻的人多、淡出的人几乎不出现），内容按"热度 × 新鲜度"挑，约 70% 的浏览看演员本领域的内容。
     * 返回 eventCount 个行为事件，加上入口来源的浏览带出的未点击曝光事件（不计入 eventCount）。
     * 还没加入社区的员工不会产生任何行为。
     */
    public List<RealtimeEvent> planCalibratedBatch(int eventCount, String batchId, LocalDateTime occurTime,
                                                   List<Employee> employees, List<Content> contents,
                                                   Function<Long, List<Comment>> commentsByContentId,
                                                   Map<String, Long> compositeChannelIds, RealtimeAudience audience) {
        List<Employee> joined = employees.stream().filter(e -> hasJoined(e, occurTime)).toList();
        List<RealtimeEvent> events = new ArrayList<>(eventCount);
        if (joined.isEmpty() || contents.isEmpty()) {
            return events;
        }
        double[] actorCumulative = cumulativeActorWeights(joined, audience.actorWeights());
        double[] contentWeights = new double[contents.size()];
        for (int i = 0; i < contents.size(); i++) {
            Content content = contents.get(i);
            // 没有发布时间的（旧数据）按刚发布算
            contentWeights[i] = ViewPlanner.attractiveness(content.getPopularityWeight(),
                content.getPublishTime() == null ? occurTime : content.getPublishTime(), occurTime);
        }

        double perLogin = 1 + VIEWS_PER_LOGIN + VIEWS_PER_LOGIN * (LIKE_PER_VIEW + SHARE_PER_VIEW + FAVORITE_PER_VIEW
            + COMMENT_PER_VIEW + COMMENT_PER_VIEW * COMMENT_LIKE_PER_COMMENT);
        double login = 1 / perLogin;
        double view = login + VIEWS_PER_LOGIN / perLogin;
        double like = view + VIEWS_PER_LOGIN * LIKE_PER_VIEW / perLogin;
        double share = like + VIEWS_PER_LOGIN * SHARE_PER_VIEW / perLogin;
        double favorite = share + VIEWS_PER_LOGIN * FAVORITE_PER_VIEW / perLogin;
        double comment = favorite + VIEWS_PER_LOGIN * COMMENT_PER_VIEW / perLogin;

        for (int i = 0; i < eventCount; i++) {
            Employee actor = joined.get(pickIndex(actorCumulative));
            double roll = random.nextDouble();
            RealtimeEvent event;
            if (roll < login) {
                event = RealtimeEvent.builder().eventType(RealtimeEventType.LOGIN).actorEmployeeId(actor.getId())
                    .occurTime(occurTime).batchId(batchId).build();
            } else {
                Content content = pickContentForActor(actor, contents, contentWeights, audience);
                if (roll < view) {
                    event = contentEvent(RealtimeEventType.VIEW, content, actor, batchId, occurTime);
                } else if (roll < like) {
                    event = contentEvent(RealtimeEventType.LIKE, content, actor, batchId, occurTime);
                } else if (roll < share) {
                    event = contentEvent(RealtimeEventType.SHARE, content, actor, batchId, occurTime);
                } else if (roll < favorite) {
                    event = contentEvent(RealtimeEventType.FAVORITE, content, actor, batchId, occurTime);
                } else if (roll < comment) {
                    event = planComment(content, actor, commentsByContentId.apply(content.getId()), batchId, occurTime);
                } else {
                    event = planCommentLike(content, actor, commentsByContentId.apply(content.getId()), batchId, occurTime);
                }
            }
            events.add(event);
            if (!compositeChannelIds.isEmpty() && event.getEventType() == RealtimeEventType.VIEW) {
                events.addAll(attachSource(event, contents, joined, compositeChannelIds));
            }
        }
        return events;
    }

    /** 演员的累计权重数组；没有任何权重（比如还没有历史登录）时每人权重相同。 */
    private static double[] cumulativeActorWeights(List<Employee> joined, Map<Long, Double> weights) {
        double[] cumulative = new double[joined.size()];
        double total = 0;
        for (int i = 0; i < joined.size(); i++) {
            total += weights.getOrDefault(joined.get(i).getId(), 0.0);
            cumulative[i] = total;
        }
        if (total <= 0) {
            for (int i = 0; i < cumulative.length; i++) {
                cumulative[i] = i + 1;
            }
        }
        return cumulative;
    }

    private int pickIndex(double[] cumulative) {
        double target = random.nextDouble() * cumulative[cumulative.length - 1];
        for (int i = 0; i < cumulative.length; i++) {
            if (target < cumulative[i]) {
                return i;
            }
        }
        return cumulative.length - 1;
    }

    /** 约 70% 先限定在演员本领域的内容里挑，没有候选就在全体里挑；都按热度 × 新鲜度加权。 */
    private Content pickContentForActor(Employee actor, List<Content> contents, double[] weights, RealtimeAudience audience) {
        if (actor.getDept() != null && random.nextDouble() < SAME_DOMAIN_RATIO) {
            double total = 0;
            for (int i = 0; i < contents.size(); i++) {
                if (actor.getDept().equals(audience.channelNames().get(contents.get(i).getChannelId()))) {
                    total += weights[i];
                }
            }
            if (total > 0) {
                double target = random.nextDouble() * total;
                double cumulative = 0;
                Content last = null;
                for (int i = 0; i < contents.size(); i++) {
                    if (actor.getDept().equals(audience.channelNames().get(contents.get(i).getChannelId()))) {
                        cumulative += weights[i];
                        last = contents.get(i);
                        if (target < cumulative) {
                            return last;
                        }
                    }
                }
                return last;
            }
        }
        double total = 0;
        for (double weight : weights) {
            total += weight;
        }
        double target = random.nextDouble() * total;
        double cumulative = 0;
        for (int i = 0; i < contents.size(); i++) {
            cumulative += weights[i];
            if (target < cumulative) {
                return contents.get(i);
            }
        }
        return contents.get(contents.size() - 1);
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
