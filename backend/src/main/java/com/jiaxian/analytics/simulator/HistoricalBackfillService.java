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
     * 每次浏览之后的互动概率（只对非沉默读者）：赞 3%、评论 0.5%、转发 0.6%、收藏 0.5%，
     * 沉默读者约占三分之一，折算后互动数约占 PV 的 3%（真实社区通常 2–5%）。
     */
    static final double LIKE_PROBABILITY = 0.03;
    static final double SHARE_PROBABILITY = 0.006;
    static final double FAVORITE_PROBABILITY = 0.005;
    static final double COMMENT_PROBABILITY = 0.005;
    private static final long LOGIN_STREAM_SALT = 998_244_353L;
    private static final long VIEW_STREAM_SALT = 1_000_000_007L;
    /** 每个搜索点击之外，平均还有这么多条没点击的搜索。 */
    static final double NO_CLICK_SEARCH_PER_CLICK = 0.4;
    /** 未点击曝光的观看者优先取同领域的人。 */
    static final double IMPRESSION_SAME_DOMAIN_RATIO = 0.7;

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
    private final ViewBatchWriter viewBatchWriter;
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
                                      ViewBatchWriter viewBatchWriter,
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
        this.viewBatchWriter = viewBatchWriter;
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
            entities.getAccounts().stream().map(a -> a.getId()).toList(), accountDepts, entities.getAccountWeights(),
            config.getHistoryStart(), config.getHistoryEnd(), random);
        Map<Long, List<FollowPlanner.Edge>> edgesByFollower = new HashMap<>();
        for (FollowPlanner.Edge edge : follows) {
            eventGenerator.generateFollow(edge.followerId(), edge.accountId(), edge.followTime(),
                edge.unfollowTime(), config.getBatchId());
            edgesByFollower.computeIfAbsent(edge.followerId(), k -> new ArrayList<>()).add(edge);
        }

        // 登录：画像员工沿用 PersonaLoginPlanner 的规划；RANDOM 员工按留存分型每天抽一次，
        // 且不早于加入社区的日期。两者都用独立随机流，不扰动回填的主随机序列。
        // 登录先于浏览规划：浏览由登录产生。
        List<LoginEvent> loginEvents = planLogins(config, entities, activityModel);

        // 浏览：每个登录日产生若干次浏览（需求驱动），时刻落在登录之后的同一天。
        List<ViewPlanner.Candidate> candidates = new ArrayList<>();
        for (Content content : entities.getContents()) {
            candidates.add(new ViewPlanner.Candidate(content.getId(), content.getPublishTime(), content.getChannelId(),
                channelNames.get(content.getChannelId()), content.getAccountId(), content.getType(),
                content.getPopularityWeight()));
        }
        ViewPlanner viewPlanner = new ViewPlanner(candidates, compositeChannelIds, edgesByFollower);
        double[] multipliers = ViewPlanner.multipliers(entities.getProfiles());
        Map<Long, EmployeeProfile> profileById = new HashMap<>();
        for (EmployeeProfile profile : entities.getProfiles()) {
            profileById.put(profile.employee().getId(), profile);
        }
        Map<Long, String> channelNameOfContent = new HashMap<>();
        for (Content content : entities.getContents()) {
            channelNameOfContent.put(content.getId(), channelNames.get(content.getChannelId()));
        }
        Map<LocalDate, List<EmployeeProfile>> activeByDay = new HashMap<>();
        for (LoginEvent login : loginEvents) {
            activeByDay.computeIfAbsent(login.time().toLocalDate(), k -> new ArrayList<>())
                .add(entities.getProfiles().get(login.profileIndex()));
        }

        Random viewRandom = new Random(config.getRandomSeed() * VIEW_STREAM_SALT);
        List<ViewBatchWriter.ViewRow> viewRows = new ArrayList<>();
        List<ViewBatchWriter.ImpressionRow> impressionRows = new ArrayList<>();
        List<ViewBatchWriter.SearchRow> searchRows = new ArrayList<>();
        List<ViewPlanner.PlannedView> plannedViews = new ArrayList<>();
        for (LoginEvent login : loginEvents) {
            EmployeeProfile viewer = entities.getProfiles().get(login.profileIndex());
            for (ViewPlanner.PlannedView view : viewPlanner.planLogin(viewer, login.time(),
                multipliers[login.profileIndex()], config.getViewsPerActiveDay(), viewRandom)) {
                plannedViews.add(view);
                viewRows.add(new ViewBatchWriter.ViewRow(view.contentId(), view.viewerId(), view.time(),
                    new ViewDetail(view.kind().viewSource(), view.entryChannelId(), view.dwellSeconds(), view.completed())));
                String channelName = channelNameOfContent.get(view.contentId());
                if (view.kind().isEntry()) {
                    // 一次点击背后还有若干"看到了但没点"的曝光，点击率 = 点击 / 曝光。
                    int unclicked = ImpressionPlanner.unclickedImpressions(view.kind(), viewRandom);
                    for (int u = 0; u < unclicked; u++) {
                        Optional<EmployeeProfile> other = pickActive(activeByDay.get(view.time().toLocalDate()),
                            view.kind() == SourceKind.OWN_ENTRY ? channelName : null, viewRandom);
                        other.ifPresent(o -> impressionRows.add(new ViewBatchWriter.ImpressionRow(
                            o.employee().getId(), view.contentId(), view.entryChannelId(), view.time(), false)));
                    }
                } else if (view.kind() == SourceKind.SEARCH) {
                    searchRows.add(new ViewBatchWriter.SearchRow(view.viewerId(),
                        TagCatalog.keyword(tagsByContent.get(view.contentId()), channelName, viewRandom),
                        view.time(), view.contentId()));
                    if (viewRandom.nextDouble() < NO_CLICK_SEARCH_PER_CLICK) {
                        pickActive(activeByDay.get(view.time().toLocalDate()), null, viewRandom).ifPresent(other ->
                            searchRows.add(new ViewBatchWriter.SearchRow(other.employee().getId(),
                                TagCatalog.keyword(tagsByContent.get(view.contentId()), channelName, viewRandom),
                                view.time(), null)));
                    }
                }
            }
        }

        Map<Long, List<Long>> authorsByContent = new HashMap<>();
        for (ContentAuthor author : contentAuthorRepository.findAll()) {
            authorsByContent.computeIfAbsent(author.getContentId(), k -> new ArrayList<>()).add(author.getEmployeeId());
        }
        viewBatchWriter.writeViews(viewRows, authorsByContent, eventGenerator.viewWeight(),
            eventGenerator.contentViewedWeight(), config.getBatchId());
        viewBatchWriter.writeImpressions(impressionRows, config.getBatchId());
        viewBatchWriter.writeSearches(searchRows, config.getBatchId());

        // 互动：只发生在非沉默读者身上，在看过之后；回复和评论点赞的时间不能越过历史结束日。
        LocalDateTime historyEndTime = config.getHistoryEnd().atStartOfDay();
        Map<Long, List<Comment>> topLevelComments = new HashMap<>();
        for (ViewPlanner.PlannedView view : plannedViews) {
            EmployeeProfile viewer = profileById.get(view.viewerId());
            if (viewer.lurker()) {
                continue;
            }
            long contentId = view.contentId();
            long viewerId = view.viewerId();
            LocalDateTime occurTime = view.time();
            if (random.nextDouble() < LIKE_PROBABILITY) {
                eventGenerator.generateLike(contentId, viewerId, occurTime, config.getBatchId());
            }
            if (random.nextDouble() < SHARE_PROBABILITY) {
                eventGenerator.generateShare(contentId, viewerId, occurTime, config.getBatchId());
            }
            if (random.nextDouble() < FAVORITE_PROBABILITY) {
                eventGenerator.generateFavorite(contentId, viewerId, occurTime, config.getBatchId());
            }
            if (random.nextDouble() < COMMENT_PROBABILITY) {
                List<Comment> existing = topLevelComments.computeIfAbsent(contentId, k -> new ArrayList<>());
                Comment comment;
                if (!existing.isEmpty() && random.nextDouble() < 0.3) {
                    Comment parent = existing.get(random.nextInt(existing.size()));
                    LocalDateTime replyTime = ActivityModel.viewTime(parent.getCreateTime(), random);
                    if (!replyTime.isBefore(historyEndTime)) {
                        continue;
                    }
                    comment = eventGenerator.generateComment(contentId, parent.getId(), viewerId, replyTime,
                        config.getBatchId());
                } else {
                    comment = eventGenerator.generateComment(contentId, null, viewerId, occurTime, config.getBatchId());
                    existing.add(comment);
                }
                if (random.nextDouble() < 0.3) {
                    LocalDateTime likeTime = ActivityModel.viewTime(comment.getCreateTime(), random);
                    if (likeTime.isBefore(historyEndTime)) {
                        viewerPicker.pick(channelNameOfContent.get(contentId), true, likeTime, random)
                            .ifPresent(liker -> eventGenerator.generateCommentLike(comment.getId(),
                                liker.employee().getId(), likeTime, config.getBatchId()));
                    }
                }
            }
        }

        // 登录占了绝大多数行，最后用 JDBC 批量写入（内容和 generateLogin 完全一样）。
        loginBatchWriter.write(loginEvents.stream()
            .map(e -> new LoginBatchWriter.Login(entities.getProfiles().get(e.profileIndex()).employee().getId(), e.time()))
            .toList(), eventGenerator.loginWeight(), config.getBatchId());

        EditorialPickGenerator editorialPickGenerator = new EditorialPickGenerator(
            contentAuthorRepository, editorialPickLogRepository,
            pointsWeightConfigRepository, pointsLedgerRepository, random);
        int pickCount = Math.max(1, entities.getContents().size() / 20);
        editorialPickGenerator.pickTopContent(entities.getContents(), pickCount,
            config.getHistoryEnd().atStartOfDay(), config.getBatchId());
    }

    private record LoginEvent(int profileIndex, LocalDateTime time) {
    }

    private List<LoginEvent> planLogins(SimulationConfig config, EntityGenerator.GeneratedEntities entities,
                                        ActivityModel activityModel) {
        List<LoginEvent> logins = new ArrayList<>();
        for (int employeeIndex = 0; employeeIndex < entities.getProfiles().size(); employeeIndex++) {
            EmployeeProfile profile = entities.getProfiles().get(employeeIndex);
            if (profile.persona() != BloggerPersona.RANDOM) {
                Random personaRandom = new Random(config.getRandomSeed() * 1_000_003L + employeeIndex);
                for (LocalDateTime loginTime : PersonaLoginPlanner.plan(profile.persona(), config.getHistoryStart(),
                        config.getHistoryEnd(), profile.joinDate(), personaRandom)) {
                    logins.add(new LoginEvent(employeeIndex, loginTime));
                }
                continue;
            }
            Random loginRandom = new Random(config.getRandomSeed() * LOGIN_STREAM_SALT + employeeIndex);
            LocalDate firstDay = profile.joinDate().isAfter(config.getHistoryStart())
                ? profile.joinDate() : config.getHistoryStart();
            for (LocalDate day = firstDay; day.isBefore(config.getHistoryEnd()); day = day.plusDays(1)) {
                if (loginRandom.nextDouble() < activityModel.dailyActivity(profile, day)) {
                    logins.add(new LoginEvent(employeeIndex, ActivityModel.timeOfDay(day, loginRandom)));
                }
            }
        }
        return logins;
    }

    /** 从某天登录过的员工里随机挑一个；preferredDept 非空时 70% 优先同领域。 */
    private static Optional<EmployeeProfile> pickActive(List<EmployeeProfile> active, String preferredDept, Random random) {
        if (active == null || active.isEmpty()) {
            return Optional.empty();
        }
        if (preferredDept != null && random.nextDouble() < IMPRESSION_SAME_DOMAIN_RATIO) {
            List<EmployeeProfile> sameDomain = new ArrayList<>();
            for (EmployeeProfile profile : active) {
                if (preferredDept.equals(profile.employee().getDept())) {
                    sameDomain.add(profile);
                }
            }
            if (!sameDomain.isEmpty()) {
                return Optional.of(sameDomain.get(random.nextInt(sameDomain.size())));
            }
        }
        return Optional.of(active.get(random.nextInt(active.size())));
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
