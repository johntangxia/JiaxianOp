package com.jiaxian.analytics.drill;

import com.jiaxian.analytics.channel.ChannelCockpitService;
import com.jiaxian.analytics.channel.ChannelCockpitService.ChannelSnapshot;
import com.jiaxian.analytics.channel.ChannelPeriod;
import com.jiaxian.analytics.channel.ChannelQueryRepository.ContentSide;
import com.jiaxian.analytics.channel.ChannelQueryRepository.DailyEntry;
import com.jiaxian.analytics.channel.ChannelQueryRepository.DailySource;
import com.jiaxian.analytics.channel.DistributionMetrics;
import com.jiaxian.analytics.channel.SourceMix;
import com.jiaxian.analytics.channel.SupplyMetrics;
import com.jiaxian.analytics.drill.SubjectQueryRepository.*;
import com.jiaxian.analytics.pv.Granularity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DrillServiceTest {

    private static final LocalDate FROM = LocalDate.of(2025, 6, 16);   // 周一
    private static final LocalDate TO = LocalDate.of(2025, 6, 29);     // 14 天
    private static final LocalDate PREV_FROM = LocalDate.of(2025, 6, 2);
    private static final LocalDate PREV_TO = LocalDate.of(2025, 6, 15);

    private final SubjectQueryRepository repository = mock(SubjectQueryRepository.class);
    private final ChannelCockpitService cockpit = mock(ChannelCockpitService.class);
    private final DrillService service = new DrillService(repository, cockpit);

    private static ContentSide side(long pv, long own) {
        return new ContentSide(pv, pv / 2, new SourceMix(own, pv - own, 0, 0, 0));
    }

    // ---- 周期与校验 ----

    @Test
    void rejectsAnInvertedOrTooLongRange() {
        assertThatThrownBy(() -> service.account(1, TO, FROM, Granularity.DAY)).isInstanceOf(InvalidDrillQueryException.class);
        assertThatThrownBy(() -> service.blogger(1, FROM, FROM.plusDays(366), Granularity.DAY))
            .isInstanceOf(InvalidDrillQueryException.class).hasMessageContaining("366");
    }

    @Test
    void unknownSubjectsAreNotFound() {
        assertThatThrownBy(() -> service.account(9, FROM, TO, Granularity.DAY)).isInstanceOf(DrillNotFoundException.class);
        assertThatThrownBy(() -> service.blogger(9, FROM, TO, Granularity.DAY)).isInstanceOf(DrillNotFoundException.class);
        assertThatThrownBy(() -> service.content(9, FROM, TO, Granularity.DAY)).isInstanceOf(DrillNotFoundException.class);
        assertThatThrownBy(() -> service.channelAccounts(9, FROM, TO)).isInstanceOf(DrillNotFoundException.class);
    }

    // ---- 频道 → 账号 ----

    @Test
    void channelAccountsListsEveryAccountSortedByPvAndKeepsQuietOnesEmpty() {
        when(repository.channel(10)).thenReturn(Optional.of(new ChannelInfo(10, "领域甲", "领域频道")));
        when(repository.accountsOfChannel(10)).thenReturn(List.of(
            new AccountInfo(1, "账号一", "PERSONAL"), new AccountInfo(2, "账号二", "TEAM"), new AccountInfo(3, "账号三", "PERSONAL")));
        SubjectFilter filter = SubjectFilter.NONE.withChannel(10);
        when(repository.contentSide(SubjectType.ACCOUNT, filter, FROM, TO)).thenReturn(Map.of(1L, side(20, 2), 2L, side(50, 10)));
        when(repository.contentSide(SubjectType.ACCOUNT, filter, PREV_FROM, PREV_TO)).thenReturn(Map.of(2L, side(40, 4)));
        when(repository.followers(any(), eq(FROM), eq(TO))).thenReturn(Map.of(2L, new FollowerMetrics(7, 3, 1)));

        ChannelAccounts result = service.channelAccounts(10, FROM, TO);

        assertThat(result.channelName()).isEqualTo("领域甲");
        assertThat(result.previousFrom()).isEqualTo(PREV_FROM);
        assertThat(result.accounts()).extracting(BreakdownRow::name).containsExactly("账号二", "账号一", "账号三");
        BreakdownRow top = result.accounts().get(0);
        assertThat(top.extra()).isEqualTo("TEAM");
        assertThat(top.current().content().pv()).isEqualTo(50);
        assertThat(top.current().content().ownEntryShare()).isEqualTo(0.2);
        assertThat(top.previous().content().pv()).isEqualTo(40);
        assertThat(top.current().followers()).isEqualTo(new FollowerMetrics(7, 3, 1));
        BreakdownRow quiet = result.accounts().get(2);
        assertThat(quiet.current().content().pv()).isZero();
        assertThat(quiet.current().followers()).isEqualTo(FollowerMetrics.EMPTY);
    }

    @Test
    void aCompositeChannelHasNothingToDrillInto() {
        when(repository.channel(1)).thenReturn(Optional.of(new ChannelInfo(1, "推荐", "综合频道")));

        assertThatThrownBy(() -> service.channelAccounts(1, FROM, TO))
            .isInstanceOf(InvalidDrillQueryException.class).hasMessageContaining("Composite");
    }

    // ---- 账号 ----

    @Test
    void accountOverviewAssemblesMetricsTrendCompositionAuthorsChannelsAndTopContents() {
        when(repository.account(1)).thenReturn(Optional.of(new AccountInfo(1, "账号一", "TEAM")));
        SubjectFilter self = SubjectFilter.ofIds(1);
        when(repository.contentSide(SubjectType.ACCOUNT, self, FROM, TO)).thenReturn(Map.of(1L, side(100, 10)));
        when(repository.interactions(SubjectType.ACCOUNT, self, FROM, TO)).thenReturn(Map.of(1L, 25L));
        when(repository.supply(SubjectType.ACCOUNT, self, FROM, TO)).thenReturn(Map.of(1L, new SupplyMetrics(4, 2)));
        when(repository.exposure(SubjectType.ACCOUNT, self, FROM, TO))
            .thenReturn(Map.of(1L, new ExposureMetrics(200, 20, 0.1, 50.0, 0.4)));
        when(repository.followers(any(), eq(FROM), eq(TO))).thenReturn(Map.of(1L, new FollowerMetrics(30, 5, 2)));
        when(repository.dailySource(SubjectType.ACCOUNT, 1, FROM, TO)).thenReturn(List.of(
            new DailySource(LocalDate.of(2025, 6, 16), 3, 4, 5, 6, 7), new DailySource(LocalDate.of(2025, 6, 24), 1, 0, 0, 0, 0)));
        when(repository.dailyExposure(SubjectType.ACCOUNT, 1, FROM, TO))
            .thenReturn(List.of(new DailyEntry(LocalDate.of(2025, 6, 17), 40, 4)));
        when(repository.dailyFollows(1, FROM, TO)).thenReturn(List.of(
            new DailyFollow(LocalDate.of(2025, 6, 18), 2, 0), new DailyFollow(LocalDate.of(2025, 6, 25), 0, 1)));
        when(repository.authorsOfAccount(1)).thenReturn(List.of(new PersonInfo(7, "作者甲"), new PersonInfo(8, "作者乙")));
        SubjectFilter inAccount = SubjectFilter.NONE.withAccount(1);
        when(repository.contentSide(SubjectType.BLOGGER, inAccount, FROM, TO)).thenReturn(Map.of(8L, side(80, 8), 7L, side(20, 2)));
        when(repository.contentSide(SubjectType.CHANNEL, inAccount, FROM, TO)).thenReturn(Map.of(10L, side(100, 10)));
        when(repository.channels(any())).thenReturn(List.of(new ChannelInfo(10, "领域甲", "领域频道")));

        AccountOverview overview = service.account(1, FROM, TO, Granularity.WEEK);

        assertThat(overview.account().name()).isEqualTo("账号一");
        assertThat(overview.current().content().pv()).isEqualTo(100);
        assertThat(overview.current().content().interactionRate()).isEqualTo(0.25);
        assertThat(overview.current().supply()).isEqualTo(new SupplyMetrics(4, 2));
        assertThat(overview.current().exposure().ctr()).isEqualTo(0.1);
        assertThat(overview.current().followers()).isEqualTo(new FollowerMetrics(30, 5, 2));
        assertThat(overview.previous().content().pv()).isZero();
        assertThat(overview.composition()).extracting(m -> m.key() + ":" + m.value())
            .containsExactly("OWN_ENTRY:10", "COMPOSITE_ENTRY:90", "SEARCH:0", "SHARE:0", "OTHER:0");

        // 两周：06-16 和 06-23 两个周一开头的周
        assertThat(overview.trend()).extracting(DrillTrendPoint::periodStart)
            .containsExactly(LocalDate.of(2025, 6, 16), LocalDate.of(2025, 6, 23));
        DrillTrendPoint week1 = overview.trend().get(0);
        assertThat(week1.ownEntry()).isEqualTo(3);
        assertThat(week1.other()).isEqualTo(7);
        assertThat(week1.impressions()).isEqualTo(40);
        assertThat(week1.ctr()).isEqualTo(0.1);
        assertThat(week1.newFollowers()).isEqualTo(2);
        DrillTrendPoint week2 = overview.trend().get(1);
        assertThat(week2.ownEntry()).isEqualTo(1);
        assertThat(week2.lostFollowers()).isEqualTo(1);
        assertThat(week2.ctr()).isNull();

        assertThat(overview.authors()).extracting(BreakdownRow::name).containsExactly("作者乙", "作者甲");
        assertThat(overview.channels()).extracting(BreakdownRow::name).containsExactly("领域甲");
        assertThat(overview.channels().get(0).extra()).isEqualTo("领域频道");
    }

    // ---- 博主 ----

    @Test
    void bloggerOverviewPassesTheKolFlagAndBreaksDownByAccountAndChannel() {
        when(repository.blogger(7)).thenReturn(Optional.of(new BloggerInfo(7, "作者甲", "半导体", LocalDate.of(2025, 1, 1), true)));
        when(repository.contentSide(SubjectType.BLOGGER, SubjectFilter.ofIds(7), FROM, TO)).thenReturn(Map.of(7L, side(60, 6)));
        SubjectFilter byAuthor = SubjectFilter.NONE.withAuthor(7);
        when(repository.contentSide(SubjectType.ACCOUNT, byAuthor, FROM, TO)).thenReturn(Map.of(1L, side(40, 4), 2L, side(20, 2)));
        when(repository.contentSide(SubjectType.CHANNEL, byAuthor, FROM, TO)).thenReturn(Map.of(10L, side(60, 6)));
        when(repository.accounts(any())).thenReturn(List.of(new AccountInfo(1, "账号一", "PERSONAL"), new AccountInfo(2, "账号二", "TEAM")));
        when(repository.channels(any())).thenReturn(List.of(new ChannelInfo(10, "领域甲", "领域频道")));

        BloggerOverview overview = service.blogger(7, FROM, TO, Granularity.DAY);

        assertThat(overview.blogger().kolProfile()).isTrue();
        assertThat(overview.current().content().pv()).isEqualTo(60);
        assertThat(overview.current().followers()).isNull();
        assertThat(overview.accounts()).extracting(BreakdownRow::name).containsExactly("账号一", "账号二");
        assertThat(overview.channels()).extracting(BreakdownRow::name).containsExactly("领域甲");
        assertThat(overview.trend()).hasSize(14);
    }

    // ---- 内容 ----

    @Test
    void contentOverviewCarriesAuthorsTagsAndEntryExposure() {
        when(repository.content(5)).thenReturn(Optional.of(
            new ContentInfo(5, "VIDEO", 10, "领域甲", 1, "账号一", LocalDateTime.of(2025, 6, 10, 9, 0))));
        when(repository.authorsOfContent(5)).thenReturn(List.of(new PersonInfo(7, "作者甲")));
        when(repository.tags(5)).thenReturn(List.of("领域甲", "测试"));
        when(repository.entryExposure(5, FROM, TO)).thenReturn(List.of(new EntryExposure(10, "领域甲", "领域频道", 30, 3)));
        when(repository.contentSide(SubjectType.CONTENT, SubjectFilter.ofIds(5), FROM, TO)).thenReturn(Map.of(5L, side(12, 2)));

        ContentOverview overview = service.content(5, FROM, TO, Granularity.DAY);

        assertThat(overview.content().type()).isEqualTo("VIDEO");
        assertThat(overview.authors()).extracting(PersonInfo::name).containsExactly("作者甲");
        assertThat(overview.tags()).containsExactly("领域甲", "测试");
        assertThat(overview.entries()).hasSize(1);
        assertThat(overview.current().content().pv()).isEqualTo(12);
    }

    // ---- 对比 ----

    @Test
    void compareRequiresTwoToFiveDistinctIdsOfASupportedType() {
        assertThatThrownBy(() -> service.compare(SubjectType.ACCOUNT, List.of(1L), FROM, TO, Granularity.DAY))
            .isInstanceOf(InvalidDrillQueryException.class);
        assertThatThrownBy(() -> service.compare(SubjectType.ACCOUNT, List.of(1L, 2L, 3L, 4L, 5L, 6L), FROM, TO, Granularity.DAY))
            .isInstanceOf(InvalidDrillQueryException.class);
        assertThatThrownBy(() -> service.compare(SubjectType.ACCOUNT, List.of(1L, 1L), FROM, TO, Granularity.DAY))
            .isInstanceOf(InvalidDrillQueryException.class);
        assertThatThrownBy(() -> service.compare(SubjectType.CONTENT, List.of(1L, 2L), FROM, TO, Granularity.DAY))
            .isInstanceOf(InvalidDrillQueryException.class);
    }

    @Test
    void compareRejectsCompositeChannelsAndUnknownIds() {
        when(repository.channels(List.of(1L, 10L))).thenReturn(List.of(
            new ChannelInfo(1, "推荐", "综合频道"), new ChannelInfo(10, "领域甲", "领域频道")));
        assertThatThrownBy(() -> service.compare(SubjectType.CHANNEL, List.of(1L, 10L), FROM, TO, Granularity.DAY))
            .isInstanceOf(InvalidDrillQueryException.class).hasMessageContaining("推荐");

        when(repository.accounts(List.of(1L, 2L))).thenReturn(List.of(new AccountInfo(1, "账号一", "TEAM")));
        assertThatThrownBy(() -> service.compare(SubjectType.ACCOUNT, List.of(1L, 2L), FROM, TO, Granularity.DAY))
            .isInstanceOf(DrillNotFoundException.class);
    }

    @Test
    void compareKeepsTheRequestedOrderAndAddsOwnEntryDistributionForChannels() {
        when(repository.channels(List.of(20L, 10L))).thenReturn(List.of(
            new ChannelInfo(10, "领域甲", "领域频道"), new ChannelInfo(20, "领域乙", "领域频道")));
        SubjectFilter ids = SubjectFilter.ofIds(List.of(20L, 10L));
        when(repository.contentSide(SubjectType.CHANNEL, ids, FROM, TO)).thenReturn(Map.of(10L, side(100, 10), 20L, side(50, 5)));
        DistributionMetrics distribution = new DistributionMetrics(100, 12, 0.12, 30, 8, 60.0);
        when(cockpit.snapshot(FROM, TO)).thenReturn(List.of(
            new ChannelSnapshot(10, "领域甲", "领域频道", new ChannelPeriod(distribution, null, null, null)),
            new ChannelSnapshot(20, "领域乙", "领域频道", new ChannelPeriod(DistributionMetrics.EMPTY, null, null, null))));

        Comparison comparison = service.compare(SubjectType.CHANNEL, List.of(20L, 10L), FROM, TO, Granularity.WEEK);

        assertThat(comparison.subjects()).extracting(CompareSubject::name).containsExactly("领域乙", "领域甲");
        assertThat(comparison.subjects().get(1).current().content().pv()).isEqualTo(100);
        assertThat(comparison.subjects().get(1).ownEntry()).isEqualTo(distribution);
        assertThat(comparison.subjects().get(0).trend()).hasSize(2);
        assertThat(comparison.subjects().get(0).previous().content().pv()).isZero();
    }

    @Test
    void compareAccountsIncludeFollowersButBloggersDoNot() {
        when(repository.accounts(List.of(1L, 2L))).thenReturn(List.of(new AccountInfo(1, "账号一", "TEAM"), new AccountInfo(2, "账号二", "PERSONAL")));
        when(repository.followers(any(), eq(FROM), eq(TO))).thenReturn(Map.of(1L, new FollowerMetrics(9, 2, 0)));
        when(repository.employees(List.of(7L, 8L))).thenReturn(List.of(new PersonInfo(7, "甲"), new PersonInfo(8, "乙")));

        Comparison accounts = service.compare(SubjectType.ACCOUNT, List.of(1L, 2L), FROM, TO, Granularity.DAY);
        Comparison bloggers = service.compare(SubjectType.BLOGGER, List.of(7L, 8L), FROM, TO, Granularity.DAY);

        assertThat(accounts.subjects().get(0).current().followers()).isEqualTo(new FollowerMetrics(9, 2, 0));
        assertThat(accounts.subjects().get(1).current().followers()).isEqualTo(FollowerMetrics.EMPTY);
        assertThat(accounts.subjects().get(0).extra()).isEqualTo("TEAM");
        assertThat(bloggers.subjects().get(0).current().followers()).isNull();
        assertThat(bloggers.subjects()).extracting(CompareSubject::name).containsExactly("甲", "乙");
    }
}
