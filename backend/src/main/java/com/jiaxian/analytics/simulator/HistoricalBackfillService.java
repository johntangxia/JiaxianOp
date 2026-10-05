package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.Channel;
import com.jiaxian.analytics.domain.Comment;
import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.ContentAuthor;
import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.growth.GrowthAlertRepository;
import com.jiaxian.analytics.growth.GrowthTaskRepository;
import com.jiaxian.analytics.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
    /**
     * 每篇内容的浏览次数 = 2 + 60 × 热度权重^0.4。热度权重按最大值归一化后绝大多数很小（均值不到 0.01），
     * 线性放大会让几乎所有内容只有 1 次浏览；开 0.4 次方压缩后，冷门内容 5–10 次、最热的内容约 62 次，
     * 仍然是明显的头部效应，又给后面的 UV、点击率等分析留下足够的数据量。
     */
    /** 每个搜索点击之外，平均还有这么多条没点击的搜索。 */
    static final double NO_CLICK_SEARCH_PER_CLICK = 0.4;
    static final int VIEW_BASE = 2;
    static final double VIEW_PEAK = 60;
    static final double VIEW_EXPONENT = 0.4;

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
    private final ImpressionRepository impressionRepository;
    private final FollowRelationRepository followRelationRepository;
    private final SearchLogRepository searchLogRepository;
    private final ContentTagRepository contentTagRepository;
    private final LoginBatchWriter loginBatchWriter;
    private final GrowthAlertRepository growthAlertRepository;
    private final GrowthTaskRepository growthTaskRepository;

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
                                      PointsLedgerRepository pointsLedgerRepository,
                                      ImpressionRepository impressionRepository,
                                      FollowRelationRepository followRelationRepository,
                                      SearchLogRepository searchLogRepository,
                                      ContentTagRepository contentTagRepository,
                                      LoginBatchWriter loginBatchWriter,
                                      GrowthAlertRepository growthAlertRepository,
                                      GrowthTaskRepository growthTaskRepository) {
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
        this.impressionRepository = impressionRepository;
        this.followRelationRepository = followRelationRepository;
        this.searchLogRepository = searchLogRepository;
        this.contentTagRepository = contentTagRepository;
        this.loginBatchWriter = loginBatchWriter;
        this.growthAlertRepository = growthAlertRepository;
        this.growthTaskRepository = growthTaskRepository;
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
            commentRepository, impressionRepository, followRelationRepository, searchLogRepository,
            contentTagRepository, random);
        eventGenerator.cacheReferenceData();

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
        Map<String, Long> compositeChannelIds = new HashMap<>();
        for (Channel channel : channelRepository.findByCategory(Channel.CATEGORY_COMPOSITE)) {
            compositeChannelIds.put(channel.getName(), channel.getId());
        }
        ActivityModel activityModel = new ActivityModel(config.getHistoryEnd());
        ViewerPicker viewerPicker = new ViewerPicker(entities.getProfiles(), activityModel);

        // 标签：领域名 + 共享标签；搜索关键词会取自这些标签。
        Map<Long, List<String>> tagsByContent = new HashMap<>();
        for (Content content : entities.getContents()) {
            List<String> tags = TagCatalog.tagsFor(channelNames.get(content.getChannelId()), random);
            tagsByContent.put(content.getId(), tags);
            eventGenerator.generateTags(content.getId(), tags, config.getBatchId());
        }

        // 关注关系要先于浏览生成：关注入口只会分发已关注账号的内容。只记录，不计分。
        Map<Long, Set<String>> accountDepts = new HashMap<>();
        Map<Long, Employee> employeeById = entities.getEmployees().stream()
            .collect(Collectors.toMap(Employee::getId, e -> e));
        for (var account : entities.getAccounts()) {
            Set<String> depts = accountMemberRepository.findByAccountId(account.getId()).stream()
                .map(m -> employeeById.get(m.getEmployeeId()).getDept()).collect(Collectors.toSet());
            accountDepts.put(account.getId(), depts);
        }
        List<FollowPlanner.Edge> follows = FollowPlanner.plan(entities.getProfiles(),
            entities.getAccounts().stream().map(a -> a.getId()).toList(), accountDepts,
            config.getHistoryStart(), config.getHistoryEnd(), random);
        Map<Long, List<FollowPlanner.Edge>> followersByAccount = new HashMap<>();
        for (FollowPlanner.Edge edge : follows) {
            eventGenerator.generateFollow(edge.followerId(), edge.accountId(), edge.followTime(),
                edge.unfollowTime(), config.getBatchId());
            followersByAccount.computeIfAbsent(edge.accountId(), k -> new ArrayList<>()).add(edge);
        }

        for (Content content : entities.getContents()) {
            List<Comment> topLevelComments = new ArrayList<>();
            String channelName = channelNames.get(content.getChannelId());
            List<String> contentTags = tagsByContent.get(content.getId());
            // 热度权重越高，围绕这篇内容产生的浏览/点赞/评论事件越多，制造头部效应。
            int viewCount = VIEW_BASE + (int) (VIEW_PEAK * Math.pow(content.getPopularityWeight(), VIEW_EXPONENT));
            for (int i = 0; i < viewCount; i++) {
                // 浏览时间：半衰期约 2 天的衰减 + 周末/上班时段规律；浏览者必须已加入社区，约 70% 来自本领域。
                LocalDateTime occurTime = ActivityModel.viewTime(content.getPublishTime(), random);

                // 先抽来源，再按来源挑浏览者：关注入口从当时正在关注该账号的人里挑，挑不出就退回推荐入口。
                SourceKind kind = SourceSampler.draw(random);
                Set<Long> followers = new HashSet<>();
                if (kind == SourceKind.FOLLOW_ENTRY) {
                    for (FollowPlanner.Edge edge : followersByAccount.getOrDefault(content.getAccountId(), List.of())) {
                        if (edge.activeAt(occurTime)) {
                            followers.add(edge.followerId());
                        }
                    }
                }
                kind = SourceSampler.applyConstraints(kind, content.getType(), !followers.isEmpty());
                Optional<EmployeeProfile> picked = kind == SourceKind.FOLLOW_ENTRY
                    ? viewerPicker.pickAmong(followers, occurTime, random)
                    : viewerPicker.pick(channelName, false, occurTime, random);
                if (picked.isEmpty() && kind == SourceKind.FOLLOW_ENTRY) {
                    kind = SourceKind.RECOMMEND_ENTRY;
                    picked = viewerPicker.pick(channelName, false, occurTime, random);
                }
                if (picked.isEmpty()) {
                    continue;
                }
                EmployeeProfile viewer = picked.get();
                Long viewerId = viewer.employee().getId();
                Long entryChannelId = SourceSampler.entryChannelId(kind, content.getChannelId(), compositeChannelIds);
                ViewDetailSampler.Dwell dwell = ViewDetailSampler.sample(content.getType(), random);
                eventGenerator.generateView(content.getId(), viewerId, occurTime, config.getBatchId(),
                    new ViewDetail(kind.viewSource(), entryChannelId, dwell.seconds(), dwell.completed()));

                if (kind.isEntry()) {
                    // 一次点击背后还有若干"看到了但没点"的曝光，点击率 = 点击 / 曝光。
                    int unclicked = ImpressionPlanner.unclickedImpressions(kind, random);
                    for (int u = 0; u < unclicked; u++) {
                        viewerPicker.pick(kind == SourceKind.OWN_ENTRY ? channelName : null, false, occurTime, random)
                            .ifPresent(other -> eventGenerator.generateImpression(content.getId(),
                                other.employee().getId(), entryChannelId, occurTime, false, config.getBatchId()));
                    }
                } else if (kind == SourceKind.SEARCH) {
                    eventGenerator.generateSearch(viewerId, TagCatalog.keyword(contentTags, channelName, random),
                        occurTime, content.getId(), config.getBatchId());
                    if (random.nextDouble() < NO_CLICK_SEARCH_PER_CLICK) {
                        viewerPicker.pick(null, false, occurTime, random).ifPresent(other ->
                            eventGenerator.generateSearch(other.employee().getId(),
                                TagCatalog.keyword(contentTags, channelName, random), occurTime, null,
                                config.getBatchId()));
                    }
                }

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
        // 登录占了绝大多数行，先收集起来最后用 JDBC 批量写入（内容和 generateLogin 完全一样）。
        List<LoginBatchWriter.Login> logins = new ArrayList<>();
        for (int employeeIndex = 0; employeeIndex < entities.getProfiles().size(); employeeIndex++) {
            EmployeeProfile profile = entities.getProfiles().get(employeeIndex);
            Long employeeId = profile.employee().getId();
            if (profile.persona() != BloggerPersona.RANDOM) {
                Random personaRandom = new Random(config.getRandomSeed() * 1_000_003L + employeeIndex);
                for (LocalDateTime loginTime : PersonaLoginPlanner.plan(
                        profile.persona(), config.getHistoryStart(), config.getHistoryEnd(), personaRandom)) {
                    logins.add(new LoginBatchWriter.Login(employeeId, loginTime));
                }
                continue;
            }
            Random loginRandom = new Random(config.getRandomSeed() * LOGIN_STREAM_SALT + employeeIndex);
            LocalDate firstDay = profile.joinDate().isAfter(config.getHistoryStart())
                ? profile.joinDate() : config.getHistoryStart();
            for (LocalDate day = firstDay; day.isBefore(config.getHistoryEnd()); day = day.plusDays(1)) {
                if (loginRandom.nextDouble() < activityModel.dailyActivity(profile, day)) {
                    logins.add(new LoginBatchWriter.Login(employeeId, ActivityModel.timeOfDay(day, loginRandom)));
                }
            }
        }

        loginBatchWriter.write(logins, eventGenerator.loginWeight(), config.getBatchId());

        EditorialPickGenerator editorialPickGenerator = new EditorialPickGenerator(
            contentAuthorRepository, editorialPickLogRepository,
            pointsWeightConfigRepository, pointsLedgerRepository, random);
        int pickCount = Math.max(1, entities.getContents().size() / 20);
        editorialPickGenerator.pickTopContent(entities.getContents(), pickCount,
            config.getHistoryEnd().atStartOfDay(), config.getBatchId());
    }

    private void clearPreviousSimulationData() {
        // 告警和任务指向的模拟对象马上会被整体重建，它们没有外键，要一并清掉
        growthTaskRepository.deleteAll();
        growthAlertRepository.deleteAll();
        impressionRepository.deleteAll();
        searchLogRepository.deleteAll();
        followRelationRepository.deleteAll();
        contentTagRepository.deleteAll();
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
