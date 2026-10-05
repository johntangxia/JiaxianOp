package com.jiaxian.analytics.channel;

import com.jiaxian.analytics.channel.ChannelQueryRepository.ChannelRef;
import com.jiaxian.analytics.channel.ChannelQueryRepository.ContentSide;
import com.jiaxian.analytics.channel.ChannelQueryRepository.DailyEntry;
import com.jiaxian.analytics.channel.ChannelQueryRepository.DailySource;
import com.jiaxian.analytics.pv.Granularity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChannelCockpitServiceTest {

    private static final LocalDate FROM = LocalDate.of(2019, 6, 10);   // 周一
    private static final LocalDate TO = LocalDate.of(2019, 6, 23);     // 14 天
    private static final LocalDate PREV_FROM = LocalDate.of(2019, 5, 27);
    private static final LocalDate PREV_TO = LocalDate.of(2019, 6, 9);

    private final ChannelQueryRepository repository = mock(ChannelQueryRepository.class);
    private final ChannelCockpitService service = new ChannelCockpitService(repository);

    @BeforeEach
    void channels() {
        when(repository.channels()).thenReturn(List.of(
            new ChannelRef(1, "推荐", "综合频道"), new ChannelRef(2, "关注", "综合频道"),
            new ChannelRef(10, "领域甲", "领域频道"), new ChannelRef(20, "领域乙", "领域频道"),
            new ChannelRef(30, "领域丙", "领域频道")));
        for (LocalDate[] range : new LocalDate[][] {{FROM, TO}, {PREV_FROM, PREV_TO}}) {
            when(repository.distribution(range[0], range[1])).thenReturn(Map.of());
            when(repository.contentSide(range[0], range[1])).thenReturn(Map.of());
            when(repository.interactions(range[0], range[1])).thenReturn(Map.of());
            when(repository.supply(range[0], range[1])).thenReturn(Map.of());
            when(repository.contentPv(range[0], range[1])).thenReturn(Map.of());
            when(repository.compositeClicksByDomain(range[0], range[1])).thenReturn(Map.of());
        }
    }

    private void currentData() {
        when(repository.distribution(FROM, TO)).thenReturn(Map.of(
            1L, new DistributionMetrics(1000, 60, 0.06, 40, 20, 50.0),
            2L, new DistributionMetrics(100, 20, 0.2, 10, 8, 70.0),
            10L, new DistributionMetrics(100, 12, 0.12, 30, 8, 80.0),
            20L, new DistributionMetrics(100, 12, 0.12, 30, 8, 80.0)));
        when(repository.contentSide(FROM, TO)).thenReturn(Map.of(
            10L, new ContentSide(200, 25, new SourceMix(12, 28, 20, 80, 60)),
            20L, new ContentSide(200, 25, new SourceMix(12, 28, 20, 80, 60)),
            30L, new ContentSide(100, 20, new SourceMix(0, 14, 10, 40, 36))));
        when(repository.interactions(FROM, TO)).thenReturn(Map.of(10L, 30L, 20L, 10L, 30L, 1L));
        when(repository.supply(FROM, TO)).thenReturn(Map.of(
            10L, new SupplyMetrics(10, 4), 20L, new SupplyMetrics(8, 3), 30L, new SupplyMetrics(1, 1)));
        when(repository.contentPv(FROM, TO)).thenReturn(Map.of(
            10L, List.of(60L, 50L, 40L, 30L, 20L), 30L, List.of(60L, 20L, 10L, 10L)));   // 仓库保证从高到低
        when(repository.compositeClicksByDomain(FROM, TO)).thenReturn(Map.of(
            1L, Map.of(10L, 20L, 20L, 8L, 30L, 4L)));
    }

    private ChannelRow row(List<ChannelRow> rows, long id) {
        return rows.stream().filter(r -> r.id() == id).findFirst().orElseThrow();
    }

    @Test
    void previousPeriodIsTheSameLengthRightBeforeTheCurrentOne() {
        Cockpit cockpit = service.cockpit(FROM, TO);

        assertThat(cockpit.previousFrom()).isEqualTo(PREV_FROM);
        assertThat(cockpit.previousTo()).isEqualTo(PREV_TO);
        assertThat(cockpit.composite()).extracting(ChannelRow::name).containsExactly("推荐", "关注");
        assertThat(cockpit.domain()).extracting(ChannelRow::name).containsExactly("领域甲", "领域乙", "领域丙");
    }

    @Test
    void domainRowsCarryContentSideSupplyAndShares() {
        currentData();

        ChannelPeriod a = row(service.cockpit(FROM, TO).domain(), 10).current();

        assertThat(a.distribution().ctr()).isEqualTo(0.12);
        assertThat(a.content().pv()).isEqualTo(200);
        assertThat(a.content().uv()).isEqualTo(25);
        assertThat(a.content().ownEntryShare()).isEqualTo(0.06);
        assertThat(a.content().compositeDependency()).isEqualTo(0.14);
        assertThat(a.content().interactionRate()).isEqualTo(0.15);
        assertThat(a.content().top1Share()).isEqualTo(0.3);        // 60 / 200
        assertThat(a.content().top3Share()).isEqualTo(0.75);       // 60 + 50 + 40 = 150
        assertThat(a.supply()).isEqualTo(new SupplyMetrics(10, 4));
        assertThat(a.coverage()).isNull();
    }

    @Test
    void compositeRowsCarryCoverageAndNoContentSide() {
        currentData();

        ChannelPeriod recommend = row(service.cockpit(FROM, TO).composite(), 1).current();

        assertThat(recommend.content()).isNull();
        assertThat(recommend.supply()).isNull();
        assertThat(recommend.coverage().domainsCovered()).isEqualTo(3);
        assertThat(recommend.coverage().topDomainShare()).isEqualTo(20 / 32.0);
        assertThat(row(service.cockpit(FROM, TO).composite(), 2).current().coverage()).isEqualTo(CompositeCoverage.EMPTY);
    }

    @Test
    void benchmarksAreMediansOfTheSameKindOfChannels() {
        currentData();

        Cockpit cockpit = service.cockpit(FROM, TO);

        // 三个领域的自主分发占比：6%、6%、0% → 中位数 6%
        assertThat(cockpit.domainBenchmark().get(Medians.OWN_ENTRY_SHARE)).isEqualTo(0.06);
        assertThat(cockpit.compositeBenchmark().get(Medians.CTR)).isEqualTo(0.13);   // (0.06 + 0.2) / 2
        assertThat(cockpit.compositeBenchmark().get(Medians.DOMAINS_WITH_CONTENT)).isEqualTo(3.0);
    }

    @Test
    void diagnosesAreAttachedToTheRows() {
        currentData();

        Cockpit cockpit = service.cockpit(FROM, TO);

        assertThat(row(cockpit.domain(), 30).diagnoses()).extracting(Diagnosis::code)
            .contains("NO_OWN_ENTRY_TRAFFIC", "HIGH_CONCENTRATION");
        assertThat(row(cockpit.domain(), 10).diagnoses()).isEmpty();
    }

    @Test
    void previousPeriodValuesAreLoadedForComparison() {
        currentData();
        when(repository.contentSide(PREV_FROM, PREV_TO)).thenReturn(Map.of(
            10L, new ContentSide(400, 30, new SourceMix(24, 56, 40, 160, 120))));

        ChannelRow row = row(service.cockpit(FROM, TO).domain(), 10);

        assertThat(row.previous().content().pv()).isEqualTo(400);
        assertThat(row.diagnoses()).extracting(Diagnosis::code).contains("PV_DROP");   // 400 → 200，下降 50%
    }

    @Test
    void channelsWithoutAnyDataGetEmptyMetricsAndNoDiagnoses() {
        Cockpit cockpit = service.cockpit(FROM, TO);

        ChannelRow row = row(cockpit.domain(), 10);
        assertThat(row.current().content().pv()).isZero();
        assertThat(row.current().content().ownEntryShare()).isNull();
        assertThat(row.current().distribution()).isEqualTo(DistributionMetrics.EMPTY);
        assertThat(row.diagnoses()).isEmpty();
        assertThat(cockpit.domainBenchmark()).isEmpty();
    }

    @Test
    void rejectsAnInvertedOrTooLongRange() {
        assertThatThrownBy(() -> service.cockpit(TO, FROM)).isInstanceOf(InvalidChannelQueryException.class);
        assertThatThrownBy(() -> service.cockpit(FROM, FROM.plusDays(366))).isInstanceOf(InvalidChannelQueryException.class);
        assertThat(service.cockpit(FROM, FROM.plusDays(365))).isNotNull();   // 恰好 366 天
    }

    @Test
    void detailBucketsTheTrendByTheRequestedGranularityAndZeroFills() {
        currentData();
        when(repository.dailyContentPv(10, FROM, TO)).thenReturn(List.of(
            new DailySource(LocalDate.of(2019, 6, 10), 2, 3, 4, 5, 6),
            new DailySource(LocalDate.of(2019, 6, 12), 1, 1, 1, 1, 1),
            new DailySource(LocalDate.of(2019, 6, 20), 7, 0, 0, 0, 0)));
        when(repository.dailyEntry(10, FROM, TO)).thenReturn(List.of(
            new DailyEntry(LocalDate.of(2019, 6, 10), 10, 2), new DailyEntry(LocalDate.of(2019, 6, 11), 10, 1)));

        ChannelDetail weekly = service.detail(10, FROM, TO, Granularity.WEEK);

        assertThat(weekly.trend()).extracting(TrendPoint::periodStart)
            .containsExactly(LocalDate.of(2019, 6, 10), LocalDate.of(2019, 6, 17));
        TrendPoint first = weekly.trend().get(0);
        assertThat(first.ownEntry()).isEqualTo(3);
        assertThat(first.compositeEntry()).isEqualTo(4);
        assertThat(first.other()).isEqualTo(7);
        assertThat(first.impressions()).isEqualTo(20);
        assertThat(first.clicks()).isEqualTo(3);
        assertThat(first.ctr()).isEqualTo(0.15);
        TrendPoint second = weekly.trend().get(1);
        assertThat(second.ownEntry()).isEqualTo(7);
        assertThat(second.ctr()).isNull();

        assertThat(service.detail(10, FROM, TO, Granularity.DAY).trend()).hasSize(14);
        assertThat(weekly.composition()).extracting(MixSlice::key)
            .containsExactly("OWN_ENTRY", "COMPOSITE_ENTRY", "SEARCH", "SHARE", "OTHER");
        assertThat(weekly.composition()).extracting(MixSlice::value).containsExactly(12L, 28L, 20L, 80L, 60L);
    }

    @Test
    void compositeDetailShowsTheClickedDomainsAndNoSourceTrend() {
        currentData();
        when(repository.dailyEntry(1, FROM, TO)).thenReturn(List.of(new DailyEntry(LocalDate.of(2019, 6, 10), 100, 6)));

        ChannelDetail detail = service.detail(1, FROM, TO, Granularity.WEEK);

        assertThat(detail.trend().get(0).ownEntry()).isZero();
        assertThat(detail.trend().get(0).clicks()).isEqualTo(6);
        assertThat(detail.composition()).extracting(MixSlice::label).containsExactly("领域甲", "领域乙", "领域丙");
        assertThat(detail.composition()).extracting(MixSlice::value).containsExactly(20L, 8L, 4L);
    }

    @Test
    void anUnknownChannelIsNotFound() {
        assertThatThrownBy(() -> service.detail(999, FROM, TO, Granularity.DAY))
            .isInstanceOf(ChannelNotFoundException.class);
    }
}
