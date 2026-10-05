package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.Channel;
import com.jiaxian.analytics.domain.Comment;
import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.ContentAuthor;
import com.jiaxian.analytics.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

@Service
public class HistoricalBackfillService {

    /**
     * 沉默读者约占三分之一，不互动；为了让互动总量和以前大致相当，非沉默读者的互动概率乘以这个系数。
     */
    static final double INTERACTION_BOOST = 1.5;
    static final double LIKE_PROBABILITY = 0.10 * INTERACTION_BOOST;
    static final double SHARE_PROBABILITY = 0.05 * INTERACTION_BOOST;
    static final double FAVORITE_PROBABILITY = 0.03 * INTERACTION_BOOST;
    static final double COMMENT_PROBABILITY = 0.08 * INTERACTION_BOOST;
    private static final long LOGIN_STREAM_SALT = 998_244_353L;

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

        Map<Long, String> channelNames = new HashMap<>();
        for (Channel channel : channelRepository.findAll()) {
            channelNames.put(channel.getId(), channel.getName());
        }
        ActivityModel activityModel = new ActivityModel(config.getHistoryEnd());
        ViewerPicker viewerPicker = new ViewerPicker(entities.getProfiles(), activityModel);

        for (Content content : entities.getContents()) {
            List<Comment> topLevelComments = new ArrayList<>();
            String channelName = channelNames.get(content.getChannelId());
            // 热度权重越高，围绕这篇内容产生的浏览/点赞/评论事件越多，制造二八效应。
            int viewCount = 1 + (int) (content.getPopularityWeight() * 50);
            for (int i = 0; i < viewCount; i++) {
                // 浏览时间：半衰期约 2 天的衰减 + 周末/上班时段规律；浏览者必须已加入社区，约 70% 来自本领域。
                LocalDateTime occurTime = ActivityModel.viewTime(content.getPublishTime(), random);
                Optional<EmployeeProfile> picked = viewerPicker.pick(channelName, false, occurTime, random);
                if (picked.isEmpty()) {
                    continue;
                }
                EmployeeProfile viewer = picked.get();
                Long viewerId = viewer.employee().getId();
                eventGenerator.generateView(content.getId(), viewerId, occurTime, config.getBatchId());

                if (viewer.lurker()) {
                    continue;
                }
                if (random.nextDouble() < LIKE_PROBABILITY) {
                    eventGenerator.generateLike(content.getId(), viewerId, occurTime, config.getBatchId());
                }
                if (random.nextDouble() < SHARE_PROBABILITY) {
                    eventGenerator.generateShare(content.getId(), viewerId, occurTime, config.getBatchId());
                }
                if (random.nextDouble() < FAVORITE_PROBABILITY) {
                    eventGenerator.generateFavorite(content.getId(), viewerId, occurTime, config.getBatchId());
                }
                if (random.nextDouble() < COMMENT_PROBABILITY) {
                    Comment comment;
                    if (!topLevelComments.isEmpty() && random.nextDouble() < 0.3) {
                        Comment parent = topLevelComments.get(random.nextInt(topLevelComments.size()));
                        LocalDateTime replyTime = ActivityModel.viewTime(parent.getCreateTime(), random);
                        comment = eventGenerator.generateComment(
                            content.getId(), parent.getId(), viewerId, replyTime, config.getBatchId());
                    } else {
                        comment = eventGenerator.generateComment(
                            content.getId(), null, viewerId, occurTime, config.getBatchId());
                        topLevelComments.add(comment);
                    }
                    if (random.nextDouble() < 0.3) {
                        LocalDateTime likeTime = ActivityModel.viewTime(comment.getCreateTime(), random);
                        Optional<EmployeeProfile> commentLiker =
                            viewerPicker.pick(channelName, true, likeTime, random);
                        commentLiker.ifPresent(liker -> eventGenerator.generateCommentLike(
                            comment.getId(), liker.employee().getId(), likeTime, config.getBatchId()));
                    }
                }
            }
        }

        // 登录：画像员工沿用 PersonaLoginPlanner 的规划；RANDOM 员工按留存分型每天抽一次，
        // 且不早于加入社区的日期。两者都用独立随机流，不扰动回填的主随机序列。
        for (int employeeIndex = 0; employeeIndex < entities.getProfiles().size(); employeeIndex++) {
            EmployeeProfile profile = entities.getProfiles().get(employeeIndex);
            Long employeeId = profile.employee().getId();
            if (profile.persona() != BloggerPersona.RANDOM) {
                Random personaRandom = new Random(config.getRandomSeed() * 1_000_003L + employeeIndex);
                for (LocalDateTime loginTime : PersonaLoginPlanner.plan(
                        profile.persona(), config.getHistoryStart(), config.getHistoryEnd(), personaRandom)) {
                    eventGenerator.generateLogin(employeeId, loginTime, config.getBatchId());
                }
                continue;
            }
            Random loginRandom = new Random(config.getRandomSeed() * LOGIN_STREAM_SALT + employeeIndex);
            LocalDate firstDay = profile.joinDate().isAfter(config.getHistoryStart())
                ? profile.joinDate() : config.getHistoryStart();
            for (LocalDate day = firstDay; day.isBefore(config.getHistoryEnd()); day = day.plusDays(1)) {
                if (loginRandom.nextDouble() < activityModel.dailyActivity(profile, day)) {
                    eventGenerator.generateLogin(employeeId, ActivityModel.timeOfDay(day, loginRandom),
                        config.getBatchId());
                }
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
        channelRepository.deleteByCategory(Channel.CATEGORY_RETIRED);
        accountMemberRepository.deleteAll();
        accountRepository.deleteAll();
        employeeRepository.deleteAll();
    }
}
