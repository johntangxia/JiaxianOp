package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.*;
import com.jiaxian.analytics.repository.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class BehaviorEventGenerator {

    private final PvLogRepository pvLogRepository;
    private final EngagementLogRepository engagementLogRepository;
    private final ActiveActionLogRepository activeActionLogRepository;
    private final ContentAuthorRepository contentAuthorRepository;
    private final PointsWeightConfigRepository pointsWeightConfigRepository;
    private final PointsLedgerRepository pointsLedgerRepository;
    private final CommentRepository commentRepository;
    private final ImpressionRepository impressionRepository;
    private final FollowRelationRepository followRelationRepository;
    private final SearchLogRepository searchLogRepository;
    private final ContentTagRepository contentTagRepository;
    private final Random random;
    /** 回填期间权重和作者都不会变，缓存起来能省掉每条事件的重复查询；实时消费者不开启（权重可以在运行时改）。 */
    private Map<String, Integer> weightCache;
    private Map<Long, List<ContentAuthor>> authorCache;

    /** 只关心积分和明细的调用方（不记录曝光、关注、搜索、标签）；用到那些方法会抛 NullPointerException。 */
    public BehaviorEventGenerator(PvLogRepository pvLogRepository,
                                   EngagementLogRepository engagementLogRepository,
                                   ActiveActionLogRepository activeActionLogRepository,
                                   ContentAuthorRepository contentAuthorRepository,
                                   PointsWeightConfigRepository pointsWeightConfigRepository,
                                   PointsLedgerRepository pointsLedgerRepository,
                                   CommentRepository commentRepository,
                                   Random random) {
        this(pvLogRepository, engagementLogRepository, activeActionLogRepository, contentAuthorRepository,
            pointsWeightConfigRepository, pointsLedgerRepository, commentRepository,
            null, null, null, null, random);
    }

    public BehaviorEventGenerator(PvLogRepository pvLogRepository,
                                   EngagementLogRepository engagementLogRepository,
                                   ActiveActionLogRepository activeActionLogRepository,
                                   ContentAuthorRepository contentAuthorRepository,
                                   PointsWeightConfigRepository pointsWeightConfigRepository,
                                   PointsLedgerRepository pointsLedgerRepository,
                                   CommentRepository commentRepository,
                                   ImpressionRepository impressionRepository,
                                   FollowRelationRepository followRelationRepository,
                                   SearchLogRepository searchLogRepository,
                                   ContentTagRepository contentTagRepository,
                                   Random random) {
        this.pvLogRepository = pvLogRepository;
        this.engagementLogRepository = engagementLogRepository;
        this.activeActionLogRepository = activeActionLogRepository;
        this.contentAuthorRepository = contentAuthorRepository;
        this.pointsWeightConfigRepository = pointsWeightConfigRepository;
        this.pointsLedgerRepository = pointsLedgerRepository;
        this.commentRepository = commentRepository;
        this.impressionRepository = impressionRepository;
        this.followRelationRepository = followRelationRepository;
        this.searchLogRepository = searchLogRepository;
        this.contentTagRepository = contentTagRepository;
        this.random = random;
    }

    /** 开启权重和内容作者的缓存；只在参考数据在这个实例的生命周期里不变时调用（历史回填）。 */
    public void cacheReferenceData() {
        weightCache = new HashMap<>();
        authorCache = new HashMap<>();
    }

    /** 当前登录的积分权重（批量写入登录时用）。 */
    public int loginWeight() {
        return weightOf("LOGIN");
    }

    private int weightOf(String actionType) {
        if (weightCache != null && weightCache.containsKey(actionType)) {
            return weightCache.get(actionType);
        }
        int weight = pointsWeightConfigRepository.findFirstByActionTypeOrderByEffectiveDateDesc(actionType)
            .map(PointsWeightConfig::getWeight)
            .orElseThrow(() -> new IllegalStateException("Missing points weight config for " + actionType));
        if (weightCache != null) {
            weightCache.put(actionType, weight);
        }
        return weight;
    }

    private List<ContentAuthor> authorsOf(Long contentId) {
        if (authorCache == null) {
            return contentAuthorRepository.findByContentId(contentId);
        }
        return authorCache.computeIfAbsent(contentId, contentAuthorRepository::findByContentId);
    }

    public void generatePost(Long contentId, Long authorId, LocalDateTime occurTime, String batchId) {
        recordActive(authorId, ActiveActionType.POST, "POST", contentId, occurTime, batchId);
    }

    public void generateView(Long contentId, Long viewerId, LocalDateTime occurTime, String batchId) {
        generateView(contentId, viewerId, occurTime, batchId, ViewDetail.NONE);
    }

    /** 记录一次浏览；来源是入口时，同时记一条"已点击"的曝光（点击率的分子）。 */
    public void generateView(Long contentId, Long viewerId, LocalDateTime occurTime, String batchId,
                             ViewDetail detail) {
        pvLogRepository.save(PvLog.builder()
            .contentId(contentId).viewerEmployeeId(viewerId).viewTime(occurTime)
            .entryChannelId(detail.entryChannelId()).sourceType(detail.source())
            .dwellSeconds(detail.dwellSeconds()).completed(detail.completed())
            .simulationBatchId(batchId).build());
        if (detail.source() == ViewSource.ENTRY && detail.entryChannelId() != null) {
            generateImpression(contentId, viewerId, detail.entryChannelId(), occurTime, true, batchId);
        }

        recordActive(viewerId, ActiveActionType.VIEW, "VIEW", contentId, occurTime, batchId);
        creditContentAuthorsPassive(contentId, "CONTENT_VIEWED", occurTime, batchId);
    }

    public void generateImpression(Long contentId, Long viewerId, Long entryChannelId, LocalDateTime occurTime,
                                   boolean clicked, String batchId) {
        impressionRepository.save(Impression.builder()
            .contentId(contentId).viewerEmployeeId(viewerId).entryChannelId(entryChannelId)
            .impressionTime(occurTime).clicked(clicked).simulationBatchId(batchId).build());
    }

    /** 关注关系只记录，不计分。 */
    public void generateFollow(Long followerId, Long accountId, LocalDateTime followTime,
                               LocalDateTime unfollowTime, String batchId) {
        followRelationRepository.save(FollowRelation.builder()
            .followerEmployeeId(followerId).accountId(accountId).followTime(followTime)
            .unfollowTime(unfollowTime).simulationBatchId(batchId).build());
    }

    public void generateSearch(Long employeeId, String keyword, LocalDateTime occurTime, Long clickedContentId,
                               String batchId) {
        searchLogRepository.save(SearchLog.builder()
            .employeeId(employeeId).keyword(keyword).searchTime(occurTime)
            .clickedContentId(clickedContentId).simulationBatchId(batchId).build());
    }

    public void generateTags(Long contentId, List<String> tags, String batchId) {
        for (String tag : tags) {
            contentTagRepository.save(ContentTag.builder()
                .contentId(contentId).tag(tag).simulationBatchId(batchId).build());
        }
    }

    public void generateLike(Long contentId, Long actorId, LocalDateTime occurTime, String batchId) {
        engagementLogRepository.save(EngagementLog.builder()
            .contentId(contentId).actorEmployeeId(actorId)
            .actionType(EngagementActionType.LIKE).occurTime(occurTime)
            .simulationBatchId(batchId).build());

        recordActive(actorId, ActiveActionType.LIKE, "LIKE", contentId, occurTime, batchId);
        creditContentAuthorsPassive(contentId, "CONTENT_LIKED", occurTime, batchId);
    }

    public void generateShare(Long contentId, Long actorId, LocalDateTime occurTime, String batchId) {
        engagementLogRepository.save(EngagementLog.builder()
            .contentId(contentId).actorEmployeeId(actorId)
            .actionType(EngagementActionType.SHARE).occurTime(occurTime)
            .simulationBatchId(batchId).build());

        recordActive(actorId, ActiveActionType.SHARE, "SHARE", contentId, occurTime, batchId);
        creditContentAuthorsPassive(contentId, "CONTENT_SHARED", occurTime, batchId);
    }

    public void generateFavorite(Long contentId, Long actorId, LocalDateTime occurTime, String batchId) {
        engagementLogRepository.save(EngagementLog.builder()
            .contentId(contentId).actorEmployeeId(actorId)
            .actionType(EngagementActionType.FAVORITE).occurTime(occurTime)
            .simulationBatchId(batchId).build());

        recordActive(actorId, ActiveActionType.FAVORITE, "FAVORITE", contentId, occurTime, batchId);
        creditContentAuthorsPassive(contentId, "CONTENT_FAVORITED", occurTime, batchId);
    }

    public void generateLogin(Long employeeId, LocalDateTime occurTime, String batchId) {
        recordActive(employeeId, ActiveActionType.LOGIN, "LOGIN", null, occurTime, batchId);
    }

    public Comment generateComment(Long contentId, Long parentCommentId, Long commenterId,
                                    LocalDateTime occurTime, String batchId) {
        Comment saved = commentRepository.save(Comment.builder()
            .contentId(contentId).employeeId(commenterId).parentCommentId(parentCommentId)
            .createTime(occurTime).simulationBatchId(batchId).build());

        recordActive(commenterId, ActiveActionType.COMMENT, "COMMENT", contentId, occurTime, batchId);

        if (parentCommentId == null) {
            creditContentAuthorsPassive(contentId, "CONTENT_COMMENTED", occurTime, batchId);
        } else {
            Comment parent = commentRepository.findById(parentCommentId)
                .orElseThrow(() -> new IllegalStateException("Parent comment not found: " + parentCommentId));
            int weight = weightOf("COMMENT_REPLIED");
            pointsLedgerRepository.save(PointsLedger.builder()
                .employeeId(parent.getEmployeeId()).actionType("COMMENT_REPLIED")
                .category(PointsCategory.PASSIVE).points(weight)
                .refId(parentCommentId).occurTime(occurTime).simulationBatchId(batchId).build());
        }

        return saved;
    }

    public void generateCommentLike(Long commentId, Long actorId, LocalDateTime occurTime, String batchId) {
        engagementLogRepository.save(EngagementLog.builder()
            .commentId(commentId).actorEmployeeId(actorId)
            .actionType(EngagementActionType.LIKE).occurTime(occurTime)
            .simulationBatchId(batchId).build());

        recordActive(actorId, ActiveActionType.LIKE, "LIKE", commentId, occurTime, batchId);

        Comment comment = commentRepository.findById(commentId)
            .orElseThrow(() -> new IllegalStateException("Comment not found: " + commentId));
        int weight = weightOf("COMMENT_LIKED");
        pointsLedgerRepository.save(PointsLedger.builder()
            .employeeId(comment.getEmployeeId()).actionType("COMMENT_LIKED")
            .category(PointsCategory.PASSIVE).points(weight)
            .refId(commentId).occurTime(occurTime).simulationBatchId(batchId).build());
    }

    private void recordActive(Long employeeId, ActiveActionType actionType, String weightActionType,
                               Long refId, LocalDateTime occurTime, String batchId) {
        activeActionLogRepository.save(ActiveActionLog.builder()
            .employeeId(employeeId).actionType(actionType).refId(refId)
            .occurTime(occurTime).simulationBatchId(batchId).build());

        int weight = weightOf(weightActionType);

        pointsLedgerRepository.save(PointsLedger.builder()
            .employeeId(employeeId).actionType(weightActionType).category(PointsCategory.ACTIVE)
            .points(weight).refId(refId).occurTime(occurTime).simulationBatchId(batchId).build());
    }

    private void creditContentAuthorsPassive(Long contentId, String weightActionType,
                                               LocalDateTime occurTime, String batchId) {
        List<ContentAuthor> authors = authorsOf(contentId);
        if (authors.isEmpty()) {
            return;
        }

        int totalWeight = weightOf(weightActionType);

        int perAuthorPoints = totalWeight / authors.size();
        for (ContentAuthor author : authors) {
            pointsLedgerRepository.save(PointsLedger.builder()
                .employeeId(author.getEmployeeId()).actionType(weightActionType)
                .category(PointsCategory.PASSIVE).points(perAuthorPoints)
                .refId(contentId).occurTime(occurTime).simulationBatchId(batchId).build());
        }
    }
}
