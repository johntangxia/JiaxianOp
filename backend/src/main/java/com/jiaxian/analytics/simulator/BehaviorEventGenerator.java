package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.*;
import com.jiaxian.analytics.repository.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

public class BehaviorEventGenerator {

    private final PvLogRepository pvLogRepository;
    private final EngagementLogRepository engagementLogRepository;
    private final ActiveActionLogRepository activeActionLogRepository;
    private final ContentAuthorRepository contentAuthorRepository;
    private final PointsWeightConfigRepository pointsWeightConfigRepository;
    private final PointsLedgerRepository pointsLedgerRepository;
    private final CommentRepository commentRepository;
    private final Random random;

    public BehaviorEventGenerator(PvLogRepository pvLogRepository,
                                   EngagementLogRepository engagementLogRepository,
                                   ActiveActionLogRepository activeActionLogRepository,
                                   ContentAuthorRepository contentAuthorRepository,
                                   PointsWeightConfigRepository pointsWeightConfigRepository,
                                   PointsLedgerRepository pointsLedgerRepository,
                                   CommentRepository commentRepository,
                                   Random random) {
        this.pvLogRepository = pvLogRepository;
        this.engagementLogRepository = engagementLogRepository;
        this.activeActionLogRepository = activeActionLogRepository;
        this.contentAuthorRepository = contentAuthorRepository;
        this.pointsWeightConfigRepository = pointsWeightConfigRepository;
        this.pointsLedgerRepository = pointsLedgerRepository;
        this.commentRepository = commentRepository;
        this.random = random;
    }

    public void generateView(Long contentId, Long viewerId, LocalDateTime occurTime, String batchId) {
        pvLogRepository.save(PvLog.builder()
            .contentId(contentId).viewerEmployeeId(viewerId).viewTime(occurTime)
            .simulationBatchId(batchId).build());

        recordActive(viewerId, ActiveActionType.VIEW, "VIEW", contentId, occurTime, batchId);
        creditContentAuthorsPassive(contentId, "CONTENT_VIEWED", occurTime, batchId);
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
            int weight = pointsWeightConfigRepository
                .findFirstByActionTypeOrderByEffectiveDateDesc("COMMENT_REPLIED")
                .map(PointsWeightConfig::getWeight)
                .orElseThrow(() -> new IllegalStateException("Missing points weight config for COMMENT_REPLIED"));
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
        int weight = pointsWeightConfigRepository
            .findFirstByActionTypeOrderByEffectiveDateDesc("COMMENT_LIKED")
            .map(PointsWeightConfig::getWeight)
            .orElseThrow(() -> new IllegalStateException("Missing points weight config for COMMENT_LIKED"));
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

        int weight = pointsWeightConfigRepository
            .findFirstByActionTypeOrderByEffectiveDateDesc(weightActionType)
            .map(PointsWeightConfig::getWeight)
            .orElseThrow(() -> new IllegalStateException("Missing points weight config for " + weightActionType));

        pointsLedgerRepository.save(PointsLedger.builder()
            .employeeId(employeeId).actionType(weightActionType).category(PointsCategory.ACTIVE)
            .points(weight).refId(refId).occurTime(occurTime).simulationBatchId(batchId).build());
    }

    private void creditContentAuthorsPassive(Long contentId, String weightActionType,
                                               LocalDateTime occurTime, String batchId) {
        List<ContentAuthor> authors = contentAuthorRepository.findByContentId(contentId);
        if (authors.isEmpty()) {
            return;
        }

        int totalWeight = pointsWeightConfigRepository
            .findFirstByActionTypeOrderByEffectiveDateDesc(weightActionType)
            .map(PointsWeightConfig::getWeight)
            .orElseThrow(() -> new IllegalStateException("Missing points weight config for " + weightActionType));

        int perAuthorPoints = totalWeight / authors.size();
        for (ContentAuthor author : authors) {
            pointsLedgerRepository.save(PointsLedger.builder()
                .employeeId(author.getEmployeeId()).actionType(weightActionType)
                .category(PointsCategory.PASSIVE).points(perAuthorPoints)
                .refId(contentId).occurTime(occurTime).simulationBatchId(batchId).build());
        }
    }
}
