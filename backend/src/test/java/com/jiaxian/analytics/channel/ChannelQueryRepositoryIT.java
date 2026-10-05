package com.jiaxian.analytics.channel;

import com.jiaxian.analytics.channel.ChannelQueryRepository.ContentSide;
import com.jiaxian.analytics.channel.ChannelQueryRepository.DailyEntry;
import com.jiaxian.analytics.channel.ChannelQueryRepository.DailySource;
import com.jiaxian.analytics.channel.ChannelQueryRepository.TopContent;
import com.jiaxian.analytics.domain.*;
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
 * 2019 年夹具：
 * <pre>
 * 领域甲(D1)：c1(账号 A1, 文章, 作者 e1)、c2(A1, 文章, 作者 e1+e2)；领域乙(D2)：c3(账号 A2, 视频, 作者 e3)；综合(C1)
 * 06-10 c1：e1 自主入口(停留 60)、e2 综合入口(40)、e3 搜索、e1 分享、e2 来源为空；c2：e1 自主入口(100)；
 *       c3：e1 综合入口(20)、e2 自主入口(30)
 * 06-11 c2：e2 分享
 * 曝光：D1 入口 5 条（2 条点击，都是 e1）、C1 入口 10 条（2 条点击）
 * 互动：06-10 e1 赞 c1、e2 转发 c1；06-11 e3 评论 c2
 * </pre>
 */
@SpringBootTest
@Transactional
class ChannelQueryRepositoryIT {

    private static final String BATCH = "channel-query-it";
    private static final LocalDate FROM = LocalDate.of(2019, 6, 10);
    private static final LocalDate TO = LocalDate.of(2019, 6, 11);

    @Autowired private ChannelQueryRepository repository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private ContentAuthorRepository contentAuthorRepository;
    @Autowired private PvLogRepository pvLogRepository;
    @Autowired private ImpressionRepository impressionRepository;
    @Autowired private EngagementLogRepository engagementLogRepository;
    @Autowired private CommentRepository commentRepository;

    private Channel d1;
    private Channel d2;
    private Channel c1;
    private Content content1;
    private Content content2;
    private Content content3;
    private Employee e1;
    private Employee e2;
    private Employee e3;

    private static LocalDateTime t(int day, int hour) {
        return LocalDateTime.of(2019, 6, day, hour, 0);
    }

    private Employee employee(String name) {
        return employeeRepository.save(Employee.builder().name(name).simulationBatchId(BATCH).build());
    }

    private Channel channel(String name, String category) {
        return channelRepository.save(Channel.builder().name(name).category(category).build());
    }

    private Content content(Channel channel, Account account, ContentType type, Employee... authors) {
        Content content = contentRepository.save(Content.builder().type(type).channelId(channel.getId())
            .accountId(account.getId()).publishTime(t(3, 9)).popularityWeight(0.5).simulationBatchId(BATCH).build());
        for (Employee author : authors) {
            contentAuthorRepository.save(ContentAuthor.builder().contentId(content.getId())
                .employeeId(author.getId()).simulationBatchId(BATCH).build());
        }
        return content;
    }

    private void view(Content content, Employee viewer, int day, ViewSource source, Channel entry, Integer dwell) {
        pvLogRepository.save(PvLog.builder().contentId(content.getId()).viewerEmployeeId(viewer.getId())
            .viewTime(t(day, 10)).sourceType(source).entryChannelId(entry == null ? null : entry.getId())
            .dwellSeconds(dwell).completed(false).simulationBatchId(BATCH).build());
    }

    private void impression(Content content, Employee viewer, Channel entry, boolean clicked) {
        impressionRepository.save(Impression.builder().contentId(content.getId()).viewerEmployeeId(viewer.getId())
            .entryChannelId(entry.getId()).impressionTime(t(10, 11)).clicked(clicked).simulationBatchId(BATCH).build());
    }

    private void engagement(Content content, Employee actor, EngagementActionType type, int day) {
        engagementLogRepository.save(EngagementLog.builder().contentId(content.getId()).actorEmployeeId(actor.getId())
            .actionType(type).occurTime(t(day, 12)).simulationBatchId(BATCH).build());
    }

    @BeforeEach
    void fixtures() {
        e1 = employee("驾驶舱测试员工1");
        e2 = employee("驾驶舱测试员工2");
        e3 = employee("驾驶舱测试员工3");
        Account a1 = accountRepository.save(Account.builder().name("驾驶舱测试账号1").type(AccountType.PERSONAL)
            .simulationBatchId(BATCH).build());
        Account a2 = accountRepository.save(Account.builder().name("驾驶舱测试账号2").type(AccountType.PERSONAL)
            .simulationBatchId(BATCH).build());
        d1 = channel("驾驶舱测试领域甲", "领域频道");
        d2 = channel("驾驶舱测试领域乙", "领域频道");
        c1 = channel("驾驶舱测试综合", "综合频道");
        content1 = content(d1, a1, ContentType.ARTICLE, e1);
        content2 = content(d1, a1, ContentType.ARTICLE, e1, e2);
        content3 = content(d2, a2, ContentType.VIDEO, e3);

        view(content1, e1, 10, ViewSource.ENTRY, d1, 60);
        view(content1, e2, 10, ViewSource.ENTRY, c1, 40);
        view(content1, e3, 10, ViewSource.SEARCH, null, 30);
        view(content1, e1, 10, ViewSource.SHARE, null, 30);
        view(content1, e2, 10, null, null, null);
        view(content2, e1, 10, ViewSource.ENTRY, d1, 100);
        view(content2, e2, 11, ViewSource.SHARE, null, 30);
        view(content3, e1, 10, ViewSource.ENTRY, c1, 20);
        view(content3, e2, 10, ViewSource.ENTRY, d2, 30);

        impression(content1, e1, d1, true);
        impression(content2, e1, d1, true);
        impression(content1, e2, d1, false);
        impression(content2, e3, d1, false);
        impression(content1, e2, d1, false);
        impression(content1, e2, c1, true);
        impression(content3, e1, c1, true);
        for (int i = 0; i < 8; i++) {
            impression(content1, e3, c1, false);
        }

        engagement(content1, e1, EngagementActionType.LIKE, 10);
        engagement(content1, e2, EngagementActionType.SHARE, 10);
        commentRepository.save(Comment.builder().contentId(content2.getId()).employeeId(e3.getId())
            .createTime(t(11, 13)).simulationBatchId(BATCH).build());
    }

    @Test
    void distributionCountsImpressionsClicksUvAndDwellPerEntryChannel() {
        Map<Long, DistributionMetrics> distribution = repository.distribution(FROM, TO);

        DistributionMetrics own = distribution.get(d1.getId());
        assertThat(own.impressions()).isEqualTo(5);
        assertThat(own.clicks()).isEqualTo(2);
        assertThat(own.ctr()).isEqualTo(0.4);
        assertThat(own.reachUv()).isEqualTo(3);
        assertThat(own.clickUv()).isEqualTo(1);
        assertThat(own.avgDwellSeconds()).isEqualTo(80.0);

        DistributionMetrics composite = distribution.get(c1.getId());
        assertThat(composite.impressions()).isEqualTo(10);
        assertThat(composite.clicks()).isEqualTo(2);
        assertThat(composite.ctr()).isEqualTo(0.2);
        assertThat(composite.avgDwellSeconds()).isEqualTo(30.0);
    }

    @Test
    void periodBoundariesAreInclusiveOfBothDaysAndExcludeOthers() {
        assertThat(repository.distribution(LocalDate.of(2019, 6, 11), LocalDate.of(2019, 6, 20))).doesNotContainKey(d1.getId());
        assertThat(repository.distribution(LocalDate.of(2019, 6, 1), LocalDate.of(2019, 6, 9))).doesNotContainKey(d1.getId());
        assertThat(repository.contentSide(LocalDate.of(2019, 6, 11), LocalDate.of(2019, 6, 11)).get(d1.getId()).pv()).isEqualTo(1);
    }

    @Test
    void contentSideSplitsPvBySourceAndTreatsMissingSourceAsOther() {
        Map<Long, ContentSide> side = repository.contentSide(FROM, TO);

        ContentSide domain1 = side.get(d1.getId());
        assertThat(domain1.pv()).isEqualTo(7);
        assertThat(domain1.uv()).isEqualTo(3);
        assertThat(domain1.mix()).isEqualTo(new SourceMix(2, 1, 1, 2, 1));
        ContentSide domain2 = side.get(d2.getId());
        assertThat(domain2.pv()).isEqualTo(2);
        assertThat(domain2.mix()).isEqualTo(new SourceMix(1, 1, 0, 0, 0));
        assertThat(side).doesNotContainKey(c1.getId());
    }

    @Test
    void interactionsCountLikesSharesFavoritesAndCommentsOnTheContentsChannel() {
        Map<Long, Long> interactions = repository.interactions(FROM, TO);

        assertThat(interactions.get(d1.getId())).isEqualTo(3);
        assertThat(interactions).doesNotContainKey(d2.getId());
    }

    @Test
    void supplyCountsPublishedContentsAndDistinctAuthorsInThePublishPeriod() {
        Map<Long, SupplyMetrics> supply = repository.supply(LocalDate.of(2019, 6, 1), LocalDate.of(2019, 6, 30));

        assertThat(supply.get(d1.getId())).isEqualTo(new SupplyMetrics(2, 2));
        assertThat(supply.get(d2.getId())).isEqualTo(new SupplyMetrics(1, 1));
        assertThat(repository.supply(FROM, TO)).doesNotContainKey(d1.getId());
    }

    @Test
    void contentPvIsListedFromHighestToLowestPerChannel() {
        Map<Long, List<Long>> pv = repository.contentPv(FROM, TO);

        assertThat(pv.get(d1.getId())).containsExactly(5L, 2L);
        assertThat(pv.get(d2.getId())).containsExactly(2L);
    }

    @Test
    void compositeClicksAreSplitByTheDomainOfTheClickedContent() {
        Map<Long, Map<Long, Long>> byDomain = repository.compositeClicksByDomain(FROM, TO);

        assertThat(byDomain.get(c1.getId())).containsEntry(d1.getId(), 1L).containsEntry(d2.getId(), 1L);
        assertThat(byDomain).doesNotContainKeys(d1.getId(), d2.getId());
    }

    @Test
    void dailyContentPvAndDailyEntryForTheDetailPage() {
        List<DailySource> daily = repository.dailyContentPv(d1.getId(), FROM, TO);
        assertThat(daily).containsExactly(
            new DailySource(LocalDate.of(2019, 6, 10), 2, 1, 1, 1, 1),
            new DailySource(LocalDate.of(2019, 6, 11), 0, 0, 0, 1, 0));

        assertThat(repository.dailyEntry(d1.getId(), FROM, TO))
            .containsExactly(new DailyEntry(LocalDate.of(2019, 6, 10), 5, 2));
        assertThat(repository.dailyEntry(d2.getId(), FROM, TO)).isEmpty();
    }

    @Test
    void topContentsOfADomainChannelAreRankedByPvWithInteractions() {
        List<TopContent> top = repository.topContents(d1.getId(), false, FROM, TO, 10);

        assertThat(top).extracting(TopContent::contentId).containsExactly(content1.getId(), content2.getId());
        assertThat(top.get(0).pv()).isEqualTo(5);
        assertThat(top.get(0).interactions()).isEqualTo(2);
        assertThat(top.get(0).accountName()).isEqualTo("驾驶舱测试账号1");
        assertThat(top.get(0).type()).isEqualTo("ARTICLE");
        assertThat(top.get(1).pv()).isEqualTo(2);
        assertThat(top.get(1).interactions()).isEqualTo(1);
        assertThat(repository.topContents(d1.getId(), false, FROM, TO, 1)).hasSize(1);
    }

    @Test
    void topContentsOfACompositeChannelAreRankedByEntryClicks() {
        List<TopContent> top = repository.topContents(c1.getId(), true, FROM, TO, 10);

        assertThat(top).extracting(TopContent::contentId).containsExactly(content1.getId(), content3.getId());
        assertThat(top).extracting(TopContent::pv).containsExactly(1L, 1L);
    }

    @Test
    void channelsListsCompositeAndDomainChannelsOnly() {
        List<ChannelQueryRepository.ChannelRef> channels = repository.channels();

        assertThat(channels).extracting(ChannelQueryRepository.ChannelRef::category)
            .containsOnly("综合频道", "领域频道");
        assertThat(channels).extracting(ChannelQueryRepository.ChannelRef::name).contains("驾驶舱测试领域甲", "推荐");
        assertThat(channels).extracting(ChannelQueryRepository.ChannelRef::id).isSorted();
    }
}
