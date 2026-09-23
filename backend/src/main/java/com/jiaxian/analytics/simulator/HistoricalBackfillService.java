package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.Comment;
import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.ContentAuthor;
import com.jiaxian.analytics.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Service
public class HistoricalBackfillService {

    private final EmployeeRepository employeeRepository;
    private final AccountRepository accountRepository;
    private final AccountMemberRepository accountMemberRepository;
    private final ChannelRepository channelRepository;
    private final ContentRepository contentRepository;
    private final ContentAuthorRepository contentAuthorRepository;
    private final CommentRepository commentRepository;
    private final PvLogRepository pvLogRepository;
    private final EngagementLogRepository engagementLogRepository;
    private final ActiveActionLogRepository activeActionLogRepository;
    private final EditorialPickLogRepository editorialPickLogRepository;
    private final PointsWeightConfigRepository pointsWeightConfigRepository;
    private final PointsLedgerRepository pointsLedgerRepository;

    public HistoricalBackfillService(EmployeeRepository employeeRepository,
                                      AccountRepository accountRepository,
                                      AccountMemberRepository accountMemberRepository,
                                      ChannelRepository channelRepository,
                                      ContentRepository contentRepository,
                                      ContentAuthorRepository contentAuthorRepository,
                                      CommentRepository commentRepository,
                                      PvLogRepository pvLogRepository,
                                      EngagementLogRepository engagementLogRepository,
                                      ActiveActionLogRepository activeActionLogRepository,
                                      EditorialPickLogRepository editorialPickLogRepository,
                                      PointsWeightConfigRepository pointsWeightConfigRepository,
                                      PointsLedgerRepository pointsLedgerRepository) {
        this.employeeRepository = employeeRepository;
        this.accountRepository = accountRepository;
        this.accountMemberRepository = accountMemberRepository;
        this.channelRepository = channelRepository;
        this.contentRepository = contentRepository;
        this.contentAuthorRepository = contentAuthorRepository;
        this.commentRepository = commentRepository;
        this.pvLogRepository = pvLogRepository;
        this.engagementLogRepository = engagementLogRepository;
        this.activeActionLogRepository = activeActionLogRepository;
        this.editorialPickLogRepository = editorialPickLogRepository;
        this.pointsWeightConfigRepository = pointsWeightConfigRepository;
        this.pointsLedgerRepository = pointsLedgerRepository;
    }

    @Transactional
    public void runBackfill(SimulationConfig config) {
        clearPreviousSimulationData();

        Random random = new Random(config.getRandomSeed());

        EntityGenerator entityGenerator = new EntityGenerator(
            employeeRepository, accountRepository, accountMemberRepository,
            channelRepository, contentRepository, contentAuthorRepository);
        EntityGenerator.GeneratedEntities entities = entityGenerator.generate(config);

        BehaviorEventGenerator eventGenerator = new BehaviorEventGenerator(
            pvLogRepository, engagementLogRepository, activeActionLogRepository,
            contentAuthorRepository, pointsWeightConfigRepository, pointsLedgerRepository,
            commentRepository, random);

        for (Content content : entities.getContents()) {
            List<ContentAuthor> authors = contentAuthorRepository.findByContentId(content.getId());
            for (ContentAuthor author : authors) {
                eventGenerator.generatePost(content.getId(), author.getEmployeeId(),
                    content.getPublishTime(), config.getBatchId());
            }
        }

        for (Content content : entities.getContents()) {
            List<Comment> topLevelComments = new ArrayList<>();
            // 热度权重越高，围绕这篇内容产生的浏览/点赞/评论事件越多，制造二八效应。
            int viewCount = 1 + (int) (content.getPopularityWeight() * 50);
            for (int i = 0; i < viewCount; i++) {
                var viewer = entities.getEmployees().get(random.nextInt(entities.getEmployees().size()));
                LocalDateTime occurTime = randomTimeAfter(content.getPublishTime(), random);
                eventGenerator.generateView(content.getId(), viewer.getId(), occurTime, config.getBatchId());

                if (random.nextDouble() < 0.1) {
                    eventGenerator.generateLike(content.getId(), viewer.getId(), occurTime, config.getBatchId());
                }
                if (random.nextDouble() < 0.05) {
                    eventGenerator.generateShare(content.getId(), viewer.getId(), occurTime, config.getBatchId());
                }
                if (random.nextDouble() < 0.03) {
                    eventGenerator.generateFavorite(content.getId(), viewer.getId(), occurTime, config.getBatchId());
                }
                if (random.nextDouble() < 0.08) {
                    Comment comment;
                    if (!topLevelComments.isEmpty() && random.nextDouble() < 0.3) {
                        Comment parent = topLevelComments.get(random.nextInt(topLevelComments.size()));
                        LocalDateTime replyTime = randomTimeAfter(parent.getCreateTime(), random);
                        comment = eventGenerator.generateComment(
                            content.getId(), parent.getId(), viewer.getId(), replyTime, config.getBatchId());
                    } else {
                        comment = eventGenerator.generateComment(
                            content.getId(), null, viewer.getId(), occurTime, config.getBatchId());
                        topLevelComments.add(comment);
                    }
                    if (random.nextDouble() < 0.3) {
                        var commentLiker = entities.getEmployees().get(random.nextInt(entities.getEmployees().size()));
                        LocalDateTime likeTime = randomTimeAfter(comment.getCreateTime(), random);
                        eventGenerator.generateCommentLike(comment.getId(), commentLiker.getId(), likeTime, config.getBatchId());
                    }
                }
            }
        }

        for (var employee : entities.getEmployees()) {
            int loginDays = 5 + random.nextInt(20);
            for (int i = 0; i < loginDays; i++) {
                LocalDateTime loginTime = randomTimeInRange(
                    config.getHistoryStart().atStartOfDay(), config.getHistoryEnd().atStartOfDay(), random);
                eventGenerator.generateLogin(employee.getId(), loginTime, config.getBatchId());
            }
        }

        EditorialPickGenerator editorialPickGenerator = new EditorialPickGenerator(
            contentAuthorRepository, editorialPickLogRepository,
            pointsWeightConfigRepository, pointsLedgerRepository, random);
        int pickCount = Math.max(1, entities.getContents().size() / 20);
        editorialPickGenerator.pickTopContent(entities.getContents(), pickCount,
            config.getHistoryEnd().atStartOfDay(), config.getBatchId());
    }

    private void clearPreviousSimulationData() {
        pointsLedgerRepository.deleteAll();
        editorialPickLogRepository.deleteAll();
        activeActionLogRepository.deleteAll();
        engagementLogRepository.deleteAll();
        pvLogRepository.deleteAll();
        commentRepository.deleteReplies();
        commentRepository.deleteAll();
        contentAuthorRepository.deleteAll();
        contentRepository.deleteAll();
        accountMemberRepository.deleteAll();
        accountRepository.deleteAll();
        employeeRepository.deleteAll();
    }

    private LocalDateTime randomTimeAfter(LocalDateTime start, Random random) {
        long maxOffsetHours = 24L * 30;
        long offset = (long) (random.nextDouble() * maxOffsetHours);
        return start.plusHours(offset);
    }

    private LocalDateTime randomTimeInRange(LocalDateTime start, LocalDateTime end, Random random) {
        long rangeHours = ChronoUnit.HOURS.between(start, end);
        long offset = (long) (random.nextDouble() * Math.max(rangeHours, 1));
        return start.plusHours(offset);
    }
}
