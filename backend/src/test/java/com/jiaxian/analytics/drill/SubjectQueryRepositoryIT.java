package com.jiaxian.analytics.drill;

import com.jiaxian.analytics.channel.ChannelQueryRepository.ContentSide;
import com.jiaxian.analytics.channel.ChannelQueryRepository.DailyEntry;
import com.jiaxian.analytics.channel.ChannelQueryRepository.DailySource;
import com.jiaxian.analytics.channel.ChannelQueryRepository.TopContent;
import com.jiaxian.analytics.channel.SourceMix;
import com.jiaxian.analytics.channel.SupplyMetrics;
import com.jiaxian.analytics.domain.*;
import com.jiaxian.analytics.drill.SubjectQueryRepository.AccountInfo;
import com.jiaxian.analytics.drill.SubjectQueryRepository.BloggerInfo;
import com.jiaxian.analytics.drill.SubjectQueryRepository.ContentInfo;
import com.jiaxian.analytics.drill.SubjectQueryRepository.DailyFollow;
import com.jiaxian.analytics.drill.SubjectQueryRepository.EntryExposure;
import com.jiaxian.analytics.drill.SubjectQueryRepository.PersonInfo;
import com.jiaxian.analytics.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2019 年夹具（周期 06-10 ~ 06-11）：
 * <pre>
 * 账号 A1（作者 e1、e2）：c1(D1, 作者 e1)、c2(D1, 作者 e1+e2)、c3(D2, 作者 e2)；账号 A2：c4(D2, 作者 e3)；综合频道 C1
 * 06-10 c1：e1 自主入口 D1(60,读完)、e2 综合入口 C1(40)、e3 搜索(30)、e1 分享(30)、e2 来源为空(无停留)
 *       c2：e1 自主入口 D1(100,读完)；c3：e1 综合入口 C1(20)、e2 自主入口 D2(30,读完)；c4：e3 搜索(30)
 * 06-11 c2：e2 分享(30)
 * 曝光：c1 在 D1 入口 3 条（1 点击）、C1 入口 2 条（1 点击）；c2 在 D1 入口 1 条（点击）；c3 在 C1 1 条、D2 1 条（都点击）
 * 互动：06-10 e1 赞 c1、e2 转发 c1；06-11 e3 评论 c2
 * 关注：e2→A1(06-01 起，一直在)、e3→A1(06-10)、e1→A1(06-02，06-11 取关)、e1→A2(06-10)
 * </pre>
 */
@SpringBootTest
@Transactional
class SubjectQueryRepositoryIT {

    private static final String BATCH = "subject-query-it";
    private static final LocalDate FROM = LocalDate.of(2019, 6, 10);
    private static final LocalDate TO = LocalDate.of(2019, 6, 11);

    @Autowired private SubjectQueryRepository repository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private ContentAuthorRepository contentAuthorRepository;
    @Autowired private PvLogRepository pvLogRepository;
    @Autowired private ImpressionRepository impressionRepository;
    @Autowired private EngagementLogRepository engagementLogRepository;
    @Autowired private CommentRepository commentRepository;
    @Autowired private FollowRelationRepository followRelationRepository;
    @Autowired private ContentTagRepository contentTagRepository;

    private Channel d1;
    private Channel d2;
    private Channel c1;
    private Account a1;
    private Account a2;
    private Employee e1;
    private Employee e2;
    private Employee e3;
    private Content content1;
    private Content content2;
    private Content content3;
    private Content content4;

    private static LocalDateTime t(int day, int hour) {
        return LocalDateTime.of(2019, 6, day, hour, 0);
    }

    private Employee employee(String name) {
        return employeeRepository.save(Employee.builder().name(name).dept("下钻测试部").communityJoinDate(LocalDate.of(2019, 1, 1))
            .simulationBatchId(BATCH).build());
    }

    private Account account(String name) {
        return accountRepository.save(Account.builder().name(name).type(AccountType.PERSONAL).simulationBatchId(BATCH).build());
    }

    private Content content(Channel channel, Account account, Employee... authors) {
        Content content = contentRepository.save(Content.builder().type(ContentType.ARTICLE).channelId(channel.getId())
            .accountId(account.getId()).publishTime(t(3, 9)).popularityWeight(0.5).simulationBatchId(BATCH).build());
        for (Employee author : authors) {
            contentAuthorRepository.save(ContentAuthor.builder().contentId(content.getId()).employeeId(author.getId())
                .simulationBatchId(BATCH).build());
        }
        return content;
    }

    private void view(Content content, Employee viewer, int day, ViewSource source, Channel entry, Integer dwell, boolean completed) {
        pvLogRepository.save(PvLog.builder().contentId(content.getId()).viewerEmployeeId(viewer.getId()).viewTime(t(day, 10))
            .sourceType(source).entryChannelId(entry == null ? null : entry.getId()).dwellSeconds(dwell)
            .completed(dwell == null ? null : completed).simulationBatchId(BATCH).build());
    }

    private void impressions(Content content, Channel entry, int shown, int clicked) {
        for (int i = 0; i < shown; i++) {
            impressionRepository.save(Impression.builder().contentId(content.getId()).viewerEmployeeId(e1.getId())
                .entryChannelId(entry.getId()).impressionTime(t(10, 11)).clicked(i < clicked).simulationBatchId(BATCH).build());
        }
    }

    private void follow(Employee follower, Account account, LocalDateTime followTime, LocalDateTime unfollowTime) {
        followRelationRepository.save(FollowRelation.builder().followerEmployeeId(follower.getId()).accountId(account.getId())
            .followTime(followTime).unfollowTime(unfollowTime).simulationBatchId(BATCH).build());
    }

    @BeforeEach
    void fixtures() {
        e1 = employee("下钻测试员工1");
        e2 = employee("下钻测试员工2");
        e3 = employee("下钻测试员工3");
        a1 = account("下钻测试账号1");
        a2 = account("下钻测试账号2");
        d1 = channelRepository.save(Channel.builder().name("下钻测试领域甲").category("领域频道").build());
        d2 = channelRepository.save(Channel.builder().name("下钻测试领域乙").category("领域频道").build());
        c1 = channelRepository.save(Channel.builder().name("下钻测试综合").category("综合频道").build());
        content1 = content(d1, a1, e1);
        content2 = content(d1, a1, e1, e2);
        content3 = content(d2, a1, e2);
        content4 = content(d2, a2, e3);

        view(content1, e1, 10, ViewSource.ENTRY, d1, 60, true);
        view(content1, e2, 10, ViewSource.ENTRY, c1, 40, false);
        view(content1, e3, 10, ViewSource.SEARCH, null, 30, false);
        view(content1, e1, 10, ViewSource.SHARE, null, 30, false);
        view(content1, e2, 10, null, null, null, false);
        view(content2, e1, 10, ViewSource.ENTRY, d1, 100, true);
        view(content2, e2, 11, ViewSource.SHARE, null, 30, false);
        view(content3, e1, 10, ViewSource.ENTRY, c1, 20, false);
        view(content3, e2, 10, ViewSource.ENTRY, d2, 30, true);
        view(content4, e3, 10, ViewSource.SEARCH, null, 30, false);

        impressions(content1, d1, 3, 1);
        impressions(content1, c1, 2, 1);
        impressions(content2, d1, 1, 1);
        impressions(content3, c1, 1, 1);
        impressions(content3, d2, 1, 1);

        engagementLogRepository.save(EngagementLog.builder().contentId(content1.getId()).actorEmployeeId(e1.getId())
            .actionType(EngagementActionType.LIKE).occurTime(t(10, 12)).simulationBatchId(BATCH).build());
        engagementLogRepository.save(EngagementLog.builder().contentId(content1.getId()).actorEmployeeId(e2.getId())
            .actionType(EngagementActionType.SHARE).occurTime(t(10, 12)).simulationBatchId(BATCH).build());
        commentRepository.save(Comment.builder().contentId(content2.getId()).employeeId(e3.getId()).createTime(t(11, 13))
            .simulationBatchId(BATCH).build());

        follow(e2, a1, t(1, 9), null);
        follow(e3, a1, t(10, 9), null);
        follow(e1, a1, t(2, 9), t(11, 12));
        follow(e1, a2, t(10, 9), null);

        contentTagRepository.save(ContentTag.builder().contentId(content1.getId()).tag("下钻测试甲").simulationBatchId(BATCH).build());
        contentTagRepository.save(ContentTag.builder().contentId(content1.getId()).tag("测试").simulationBatchId(BATCH).build());
    }

    @Test
    void contentSideGroupsByChannelAccountBloggerAndContent() {
        Map<Long, ContentSide> byChannel = repository.contentSide(SubjectType.CHANNEL, SubjectFilter.NONE, FROM, TO);
        assertThat(byChannel.get(d1.getId()).pv()).isEqualTo(7);
        assertThat(byChannel.get(d2.getId()).pv()).isEqualTo(3);

        Map<Long, ContentSide> byAccount = repository.contentSide(SubjectType.ACCOUNT, SubjectFilter.NONE, FROM, TO);
        assertThat(byAccount.get(a1.getId()).pv()).isEqualTo(9);
        assertThat(byAccount.get(a1.getId()).uv()).isEqualTo(3);
        assertThat(byAccount.get(a1.getId()).mix()).isEqualTo(new SourceMix(3, 2, 1, 2, 1));
        assertThat(byAccount.get(a2.getId()).pv()).isEqualTo(1);

        // 合著内容每位作者都记完整 PV：c2 的 2 次浏览同时算给 e1 和 e2
        Map<Long, ContentSide> byBlogger = repository.contentSide(SubjectType.BLOGGER, SubjectFilter.NONE, FROM, TO);
        assertThat(byBlogger.get(e1.getId()).pv()).isEqualTo(7);
        assertThat(byBlogger.get(e2.getId()).pv()).isEqualTo(4);
        assertThat(byBlogger.get(e3.getId()).pv()).isEqualTo(1);

        Map<Long, ContentSide> byContent = repository.contentSide(SubjectType.CONTENT, SubjectFilter.ofIds(content2.getId()), FROM, TO);
        assertThat(byContent).containsOnlyKeys(content2.getId());
        assertThat(byContent.get(content2.getId()).pv()).isEqualTo(2);
    }

    @Test
    void filtersCombineForBreakdowns() {
        // 账号 A1 在各频道的分布
        Map<Long, ContentSide> channelsOfAccount =
            repository.contentSide(SubjectType.CHANNEL, SubjectFilter.NONE.withAccount(a1.getId()), FROM, TO);
        assertThat(channelsOfAccount.get(d1.getId()).pv()).isEqualTo(7);
        assertThat(channelsOfAccount.get(d2.getId()).pv()).isEqualTo(2);

        // 频道 D2 下各账号
        Map<Long, ContentSide> accountsOfChannel =
            repository.contentSide(SubjectType.ACCOUNT, SubjectFilter.NONE.withChannel(d2.getId()), FROM, TO);
        assertThat(accountsOfChannel.get(a1.getId()).pv()).isEqualTo(2);
        assertThat(accountsOfChannel.get(a2.getId()).pv()).isEqualTo(1);

        // 作者 e1 的内容按账号分
        Map<Long, ContentSide> accountsOfAuthor =
            repository.contentSide(SubjectType.ACCOUNT, SubjectFilter.NONE.withAuthor(e1.getId()), FROM, TO);
        assertThat(accountsOfAuthor).containsOnlyKeys(a1.getId());
        assertThat(accountsOfAuthor.get(a1.getId()).pv()).isEqualTo(7);

        // 账号 A1 里各作者
        Map<Long, ContentSide> authorsOfAccount =
            repository.contentSide(SubjectType.BLOGGER, SubjectFilter.NONE.withAccount(a1.getId()), FROM, TO);
        assertThat(authorsOfAccount.get(e1.getId()).pv()).isEqualTo(7);
        assertThat(authorsOfAccount.get(e2.getId()).pv()).isEqualTo(4);
        assertThat(authorsOfAccount).doesNotContainKey(e3.getId());
    }

    @Test
    void anEmptyIdListMatchesNothing() {
        assertThat(repository.contentSide(SubjectType.ACCOUNT, SubjectFilter.ofIds(List.of()), FROM, TO)).isEmpty();
    }

    @Test
    void interactionsAndSupplyAndConcentration() {
        Map<Long, Long> interactions = repository.interactions(SubjectType.ACCOUNT, SubjectFilter.NONE, FROM, TO);
        assertThat(interactions.get(a1.getId())).isEqualTo(3);
        assertThat(interactions).doesNotContainKey(a2.getId());
        Map<Long, Long> byAuthor = repository.interactions(SubjectType.BLOGGER, SubjectFilter.NONE, FROM, TO);
        assertThat(byAuthor.get(e1.getId())).isEqualTo(3);
        assertThat(byAuthor.get(e2.getId())).isEqualTo(1);   // c2 上的评论

        Map<Long, SupplyMetrics> supply =
            repository.supply(SubjectType.ACCOUNT, SubjectFilter.NONE, LocalDate.of(2019, 6, 1), LocalDate.of(2019, 6, 30));
        assertThat(supply.get(a1.getId())).isEqualTo(new SupplyMetrics(3, 2));
        assertThat(supply.get(a2.getId())).isEqualTo(new SupplyMetrics(1, 1));
        assertThat(repository.supply(SubjectType.BLOGGER, SubjectFilter.NONE, LocalDate.of(2019, 6, 1), LocalDate.of(2019, 6, 30))
            .get(e1.getId()).contents()).isEqualTo(2);

        assertThat(repository.contentPv(SubjectType.ACCOUNT, SubjectFilter.NONE, FROM, TO).get(a1.getId()))
            .containsExactly(5L, 2L, 2L);
    }

    @Test
    void exposureCombinesImpressionsClicksDwellAndCompletion() {
        Map<Long, ExposureMetrics> exposure = repository.exposure(SubjectType.ACCOUNT, SubjectFilter.NONE, FROM, TO);

        ExposureMetrics first = exposure.get(a1.getId());
        assertThat(first.impressions()).isEqualTo(8);
        assertThat(first.clicks()).isEqualTo(5);
        assertThat(first.ctr()).isEqualTo(5 / 8.0);
        assertThat(first.avgDwellSeconds()).isEqualTo(42.5);       // 来源为空的那次没有停留，不参与平均
        assertThat(first.completionRate()).isEqualTo(3 / 8.0);

        ExposureMetrics second = exposure.get(a2.getId());
        assertThat(second.impressions()).isZero();
        assertThat(second.ctr()).isNull();
        assertThat(second.avgDwellSeconds()).isEqualTo(30.0);
        assertThat(second.completionRate()).isEqualTo(0.0);
    }

    @Test
    void followersCountActiveNewAndLost() {
        Map<Long, FollowerMetrics> followers = repository.followers(List.of(a1.getId(), a2.getId()), FROM, TO);

        assertThat(followers.get(a1.getId())).isEqualTo(new FollowerMetrics(2, 1, 1));
        assertThat(followers.get(a2.getId())).isEqualTo(new FollowerMetrics(1, 1, 0));
        assertThat(repository.followers(List.of(), FROM, TO)).isEmpty();
        // 06-10 末：e1→A1 还没取关，所以是 3 人
        assertThat(repository.followers(List.of(a1.getId()), FROM, FROM).get(a1.getId()).followers()).isEqualTo(3);
        assertThat(repository.dailyFollows(a1.getId(), FROM, TO)).containsExactly(
            new DailyFollow(LocalDate.of(2019, 6, 10), 1, 0), new DailyFollow(LocalDate.of(2019, 6, 11), 0, 1));
    }

    @Test
    void dailySeriesForASubject() {
        assertThat(repository.dailySource(SubjectType.ACCOUNT, a1.getId(), FROM, TO)).containsExactly(
            new DailySource(LocalDate.of(2019, 6, 10), 3, 2, 1, 1, 1),
            new DailySource(LocalDate.of(2019, 6, 11), 0, 0, 0, 1, 0));
        assertThat(repository.dailyExposure(SubjectType.ACCOUNT, a1.getId(), FROM, TO))
            .containsExactly(new DailyEntry(LocalDate.of(2019, 6, 10), 8, 5));
        assertThat(repository.dailySource(SubjectType.BLOGGER, e3.getId(), FROM, TO)).containsExactly(
            new DailySource(LocalDate.of(2019, 6, 10), 0, 0, 1, 0, 0));
    }

    @Test
    void topContentsPerSubject() {
        List<TopContent> account = repository.topContents(SubjectType.ACCOUNT, SubjectFilter.ofIds(a1.getId()), FROM, TO, 10);
        assertThat(account).extracting(TopContent::contentId)
            .containsExactly(content1.getId(), content2.getId(), content3.getId());
        assertThat(account.get(0).pv()).isEqualTo(5);
        assertThat(account.get(0).interactions()).isEqualTo(2);
        assertThat(account.get(1).interactions()).isEqualTo(1);

        assertThat(repository.topContents(SubjectType.BLOGGER, SubjectFilter.ofIds(e2.getId()), FROM, TO, 10))
            .extracting(TopContent::contentId).containsExactly(content2.getId(), content3.getId());
        assertThat(repository.topContents(SubjectType.CHANNEL, SubjectFilter.ofIds(d2.getId()), FROM, TO, 1)).hasSize(1);
    }

    @Test
    void entryExposureOfAContent() {
        List<EntryExposure> entries = repository.entryExposure(content1.getId(), FROM, TO);

        assertThat(entries).extracting(EntryExposure::channelId).containsExactly(d1.getId(), c1.getId());
        assertThat(entries.get(0).impressions()).isEqualTo(3);
        assertThat(entries.get(0).clicks()).isEqualTo(1);
        assertThat(entries.get(1).category()).isEqualTo("综合频道");
    }

    @Test
    void subjectInformation() {
        assertThat(repository.account(a1.getId())).contains(new AccountInfo(a1.getId(), "下钻测试账号1", "PERSONAL"));
        assertThat(repository.account(-1)).isEmpty();

        BloggerInfo blogger = repository.blogger(e1.getId()).orElseThrow();
        assertThat(blogger.name()).isEqualTo("下钻测试员工1");
        assertThat(blogger.dept()).isEqualTo("下钻测试部");
        assertThat(blogger.communityJoinDate()).isEqualTo(LocalDate.of(2019, 1, 1));
        assertThat(blogger.kolProfile()).isFalse();

        ContentInfo info = repository.content(content1.getId()).orElseThrow();
        assertThat(info.channelName()).isEqualTo("下钻测试领域甲");
        assertThat(info.accountName()).isEqualTo("下钻测试账号1");
        assertThat(repository.content(-1)).isEmpty();

        assertThat(repository.channel(d1.getId())).isPresent();
        assertThat(repository.channel(-1)).isEmpty();
        assertThat(repository.accountsOfChannel(d2.getId())).extracting(AccountInfo::id).containsExactly(a1.getId(), a2.getId());
        assertThat(repository.authorsOfAccount(a1.getId())).extracting(PersonInfo::id).containsExactly(e1.getId(), e2.getId());
        assertThat(repository.authorsOfContent(content2.getId())).extracting(PersonInfo::id).containsExactly(e1.getId(), e2.getId());
        assertThat(repository.tags(content1.getId())).containsExactly("下钻测试甲", "测试");
        assertThat(repository.channels(List.of(d1.getId(), c1.getId()))).hasSize(2);
        assertThat(repository.accounts(List.of(a2.getId()))).hasSize(1);
        assertThat(repository.employees(List.of(e1.getId(), e3.getId()))).hasSize(2);
    }
}
