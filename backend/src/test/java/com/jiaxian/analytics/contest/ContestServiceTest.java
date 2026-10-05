package com.jiaxian.analytics.contest;

import com.jiaxian.analytics.channel.*;
import com.jiaxian.analytics.channel.ChannelCockpitService.ChannelSnapshot;
import com.jiaxian.analytics.contest.ContestRepository.DataRange;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContestServiceTest {

    private static final LocalDate WEEK = LocalDate.of(2025, 6, 16);        // 周一
    private static final LocalDate WEEK_END = LocalDate.of(2025, 6, 22);
    private static final LocalDate PREV_WEEK = LocalDate.of(2025, 6, 9);
    private static final LocalDate PREV_WEEK_END = LocalDate.of(2025, 6, 15);

    private final ChannelCockpitService cockpit = mock(ChannelCockpitService.class);
    private final ContestRepository repository = mock(ContestRepository.class);
    private final ContestService service = new ContestService(cockpit, repository);

    private static ChannelPeriod domain(long pv, Double ownShare, Double ctr, Double interactionRate, long uv, long contents) {
        long clicks = ownShare == null ? 0 : Math.round(pv * ownShare);
        return new ChannelPeriod(new DistributionMetrics(ctr == null ? 0 : 100, clicks, ctr, 10, 5, 60.0),
            new ContentMetrics(pv, uv, 0, interactionRate, null, null, SourceMix.EMPTY, ownShare, null),
            new SupplyMetrics(contents, contents), null);
    }

    private static ChannelPeriod composite(long impressions, long clicks, Double ctr, long uv, int domains) {
        return new ChannelPeriod(new DistributionMetrics(impressions, clicks, ctr, uv, uv, 60.0), null, null,
            new CompositeCoverage(domains, 0.3));
    }

    private static ChannelSnapshot d(long id, String name, ChannelPeriod period) {
        return new ChannelSnapshot(id, name, "领域频道", period);
    }

    private static ChannelSnapshot c(long id, String name, ChannelPeriod period) {
        return new ChannelSnapshot(id, name, "综合频道", period);
    }

    /** 本期：甲最强、丙最弱；上期：丙最强、甲最弱。 */
    private void stubDomainLeague() {
        when(cockpit.snapshot(WEEK, WEEK_END)).thenReturn(List.of(
            d(10, "领域甲", domain(300, 0.10, 0.15, 0.20, 80, 10)),
            d(20, "领域乙", domain(200, 0.05, 0.10, 0.10, 50, 6)),
            d(30, "领域丙", domain(100, 0.01, 0.05, 0.05, 20, 2))));
        when(cockpit.snapshot(PREV_WEEK, PREV_WEEK_END)).thenReturn(List.of(
            d(10, "领域甲", domain(100, 0.01, 0.05, 0.05, 20, 2)),
            d(20, "领域乙", domain(200, 0.05, 0.10, 0.10, 50, 6)),
            d(30, "领域丙", domain(300, 0.10, 0.15, 0.20, 80, 10))));
    }

    // ---- 周期 ----

    @Test
    void weeklyPeriodsListNewestFirstAndFlagTheIncompleteOne() {
        when(repository.pvDataRange()).thenReturn(Optional.of(new DataRange(LocalDate.of(2025, 6, 4), LocalDate.of(2025, 6, 25))));

        List<PeriodOption> periods = service.periods(ContestType.WEEK);

        assertThat(periods).extracting(PeriodOption::periodStart).containsExactly(
            LocalDate.of(2025, 6, 23), LocalDate.of(2025, 6, 16), LocalDate.of(2025, 6, 9), LocalDate.of(2025, 6, 2));
        assertThat(periods).extracting(PeriodOption::complete).containsExactly(false, true, true, true);
        assertThat(periods.get(1).periodEnd()).isEqualTo(LocalDate.of(2025, 6, 22));
    }

    @Test
    void monthlyPeriodsUseCalendarMonths() {
        when(repository.pvDataRange()).thenReturn(Optional.of(new DataRange(LocalDate.of(2025, 4, 10), LocalDate.of(2025, 6, 25))));

        List<PeriodOption> periods = service.periods(ContestType.MONTH);

        assertThat(periods).extracting(PeriodOption::periodStart).containsExactly(
            LocalDate.of(2025, 6, 1), LocalDate.of(2025, 5, 1), LocalDate.of(2025, 4, 1));
        assertThat(periods).extracting(PeriodOption::complete).containsExactly(false, true, true);
        assertThat(periods.get(1).periodEnd()).isEqualTo(LocalDate.of(2025, 5, 31));
    }

    @Test
    void noDataMeansNoPeriods() {
        when(repository.pvDataRange()).thenReturn(Optional.empty());

        assertThat(service.periods(ContestType.WEEK)).isEmpty();
    }

    @Test
    void periodsAreCappedAtFiftyTwo() {
        when(repository.pvDataRange()).thenReturn(Optional.of(new DataRange(LocalDate.of(2020, 1, 1), LocalDate.of(2025, 6, 25))));

        assertThat(service.periods(ContestType.WEEK)).hasSize(52);
    }

    // ---- 榜单 ----

    @Test
    void periodStartMustBeTheFirstDayOfAWeekOrMonth() {
        assertThatThrownBy(() -> service.boards(ContestType.WEEK, LocalDate.of(2025, 6, 17)))
            .isInstanceOf(InvalidContestQueryException.class).hasMessageContaining("Monday");
        assertThatThrownBy(() -> service.boards(ContestType.MONTH, LocalDate.of(2025, 6, 2)))
            .isInstanceOf(InvalidContestQueryException.class);
    }

    @Test
    void theBoardCoversTheRequestedPeriodAndTheCalendarPeriodBefore() {
        stubDomainLeague();

        ContestBoards boards = service.boards(ContestType.WEEK, WEEK);

        assertThat(boards.periodEnd()).isEqualTo(WEEK_END);
        assertThat(boards.previousStart()).isEqualTo(PREV_WEEK);
        assertThat(boards.previousEnd()).isEqualTo(PREV_WEEK_END);
    }

    @Test
    void aMonthlyBoardComparesWithTheWholePreviousMonth() {
        when(cockpit.snapshot(LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 31))).thenReturn(List.of());
        when(cockpit.snapshot(LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 28))).thenReturn(List.of());

        ContestBoards boards = service.boards(ContestType.MONTH, LocalDate.of(2025, 3, 1));

        assertThat(boards.previousStart()).isEqualTo(LocalDate.of(2025, 2, 1));
        assertThat(boards.previousEnd()).isEqualTo(LocalDate.of(2025, 2, 28));
    }

    @Test
    void domainChannelsAreRankedByHealthScoreWithRankAndScoreChanges() {
        stubDomainLeague();

        List<ContestEntry> domain = service.boards(ContestType.WEEK, WEEK).domain();

        assertThat(domain).extracting(ContestEntry::name).containsExactly("领域甲", "领域乙", "领域丙");
        assertThat(domain).extracting(ContestEntry::rank).containsExactly(1, 2, 3);
        assertThat(domain.get(0).score()).isEqualTo(100.0);
        assertThat(domain.get(2).score()).isEqualTo(0.0);
        // 甲：上期第 3 → 本期第 1，上升 2 名，分数 0 → 100
        assertThat(domain.get(0).previousRank()).isEqualTo(3);
        assertThat(domain.get(0).rankChange()).isEqualTo(2);
        assertThat(domain.get(0).previousScore()).isEqualTo(0.0);
        assertThat(domain.get(0).scoreChange()).isEqualTo(100.0);
        // 乙：前后一样
        assertThat(domain.get(1).rankChange()).isZero();
        assertThat(domain.get(1).scoreChange()).isZero();
        // 丙：下降 2 名
        assertThat(domain.get(2).rankChange()).isEqualTo(-2);
        assertThat(domain.get(2).scoreChange()).isEqualTo(-100.0);
    }

    @Test
    void eachRowCarriesFiveDomainDimensionsWithWeightsSummingToOne() {
        stubDomainLeague();

        ContestEntry first = service.boards(ContestType.WEEK, WEEK).domain().get(0);

        assertThat(first.dimensions()).extracting(DimensionScore::label)
            .containsExactly("自主分发", "流量", "互动", "触达用户", "内容供给");
        assertThat(first.dimensions().stream().mapToDouble(DimensionScore::weight).sum()).isEqualTo(1.0);
        assertThat(first.strongest()).isNotNull();
        assertThat(first.weakest()).isNotNull();
    }

    @Test
    void strongestAndWeakestAreTheBestAndWorstDimensions() {
        // 丙：流量第 1，其他都最差
        when(cockpit.snapshot(WEEK, WEEK_END)).thenReturn(List.of(
            d(10, "领域甲", domain(100, 0.10, 0.15, 0.20, 80, 10)),
            d(30, "领域丙", domain(900, 0.0, 0.0, 0.0, 10, 1))));
        when(cockpit.snapshot(PREV_WEEK, PREV_WEEK_END)).thenReturn(List.of());

        ContestEntry third = service.boards(ContestType.WEEK, WEEK).domain().stream()
            .filter(e -> e.channelId() == 30).findFirst().orElseThrow();

        assertThat(third.strongest()).isEqualTo("流量");
        assertThat(third.weakest()).isEqualTo("自主分发");
    }

    @Test
    void whenThePreviousPeriodHasNoDataThereAreNoChanges() {
        when(cockpit.snapshot(WEEK, WEEK_END)).thenReturn(List.of(
            d(10, "领域甲", domain(300, 0.10, 0.15, 0.20, 80, 10)), d(20, "领域乙", domain(200, 0.05, 0.10, 0.10, 50, 6))));
        when(cockpit.snapshot(PREV_WEEK, PREV_WEEK_END)).thenReturn(List.of(
            d(10, "领域甲", domain(0, null, null, null, 0, 0)), d(20, "领域乙", domain(0, null, null, null, 0, 0))));

        ContestBoards boards = service.boards(ContestType.WEEK, WEEK);

        assertThat(boards.previousHasData()).isFalse();
        assertThat(boards.domain()).allSatisfy(e -> {
            assertThat(e.previousRank()).isNull();
            assertThat(e.rankChange()).isNull();
            assertThat(e.previousScore()).isNull();
            assertThat(e.scoreChange()).isNull();
        });
        assertThat(boards.improvers()).isEmpty();
    }

    @Test
    void improversAreTheDomainChannelsWithTheBiggestPositiveScoreGain() {
        stubDomainLeague();

        List<ContestEntry> improvers = service.boards(ContestType.WEEK, WEEK).improvers();

        assertThat(improvers).extracting(ContestEntry::name).containsExactly("领域甲");   // 乙持平、丙下降
    }

    @Test
    void improversAreCappedAtFive() {
        // 本期 12 个频道各不相同；上期所有频道指标相同（全部并列 50 分）→ 排名前半的 6 个都是进步，只取前 5
        List<ChannelSnapshot> now = new java.util.ArrayList<>();
        List<ChannelSnapshot> before = new java.util.ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            now.add(d(i, "领域" + i, domain(100L * i, 0.01 * i, 0.01 * i, 0.01 * i, 10L * i, i)));
            before.add(d(i, "领域" + i, domain(100, 0.05, 0.05, 0.05, 10, 5)));
        }
        when(cockpit.snapshot(WEEK, WEEK_END)).thenReturn(now);
        when(cockpit.snapshot(PREV_WEEK, PREV_WEEK_END)).thenReturn(before);

        List<ContestEntry> improvers = service.boards(ContestType.WEEK, WEEK).improvers();

        assertThat(improvers).hasSize(5);
        assertThat(improvers).extracting(ContestEntry::scoreChange).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(improvers.get(0).name()).isEqualTo("领域12");
    }

    @Test
    void compositeChannelsHaveTheirOwnLeagueAndDimensions() {
        stubDomainLeague();
        when(cockpit.snapshot(WEEK, WEEK_END)).thenReturn(List.of(
            c(1, "推荐", composite(1000, 40, 0.04, 30, 10)), c(2, "关注", composite(100, 20, 0.20, 10, 3)),
            d(10, "领域甲", domain(300, 0.10, 0.15, 0.20, 80, 10))));

        ContestBoards boards = service.boards(ContestType.WEEK, WEEK);

        assertThat(boards.composite()).extracting(ContestEntry::name).containsExactlyInAnyOrder("推荐", "关注");
        assertThat(boards.domain()).extracting(ContestEntry::name).containsExactly("领域甲");
        assertThat(boards.composite().get(0).dimensions()).extracting(DimensionScore::label)
            .containsExactly("点击率", "曝光", "点击", "触达用户", "领域覆盖");
        assertThat(boards.composite().stream().mapToInt(ContestEntry::rank).toArray()).containsExactly(1, 2);
    }
}
