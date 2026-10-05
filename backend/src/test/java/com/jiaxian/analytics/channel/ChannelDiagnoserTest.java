package com.jiaxian.analytics.channel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelDiagnoserTest {

    private static final Map<String, Double> DOMAIN_BENCH = Map.of(
        Medians.OWN_ENTRY_SHARE, 0.02, Medians.COMPOSITE_DEPENDENCY, 0.14, Medians.OWN_CTR, 0.12,
        Medians.INTERACTION_RATE, 0.10, Medians.CONTENTS, 8.0);

    /** 一个领域频道周期：pv 次浏览，其中 own 次来自自主入口、composite 次来自综合入口。 */
    private static ChannelPeriod domain(long pv, long own, long composite, long interactions, long top1,
                                        long impressions, long contents) {
        SourceMix mix = new SourceMix(own, composite, 0, pv - own - composite, 0);
        ContentMetrics content = new ContentMetrics(pv, pv / 2, interactions,
            pv == 0 ? null : interactions / (double) pv, pv == 0 ? null : top1 / (double) pv,
            pv == 0 ? null : Math.min(1.0, 3 * top1 / (double) pv), mix,
            pv == 0 ? null : own / (double) pv, pv == 0 ? null : composite / (double) pv);
        DistributionMetrics distribution = new DistributionMetrics(impressions, own,
            impressions == 0 ? null : own / (double) impressions, impressions, own, 60.0);
        return new ChannelPeriod(distribution, content, new SupplyMetrics(contents, contents), null);
    }

    private static ChannelPeriod healthyDomain() {
        return domain(200, 6, 28, 24, 20, 50, 9);   // 自主 CTR = 6 / 50 = 12%
    }

    private static List<String> codes(List<Diagnosis> diagnoses) {
        return diagnoses.stream().map(Diagnosis::code).toList();
    }

    @Test
    void aHealthyDomainChannelHasNoDiagnoses() {
        assertThat(ChannelDiagnoser.diagnoseDomain(healthyDomain(), healthyDomain(), DOMAIN_BENCH)).isEmpty();
    }

    @Test
    void noOwnEntryTrafficIsCriticalAndReplacesTheLowShareWarning() {
        List<Diagnosis> result = ChannelDiagnoser.diagnoseDomain(domain(200, 0, 28, 24, 20, 100, 9), null, DOMAIN_BENCH);

        assertThat(codes(result)).contains("NO_OWN_ENTRY_TRAFFIC").doesNotContain("LOW_OWN_ENTRY_SHARE");
        assertThat(result.stream().filter(d -> d.code().equals("NO_OWN_ENTRY_TRAFFIC")).findFirst().orElseThrow()
            .severity()).isEqualTo(Diagnosis.CRITICAL);
    }

    @Test
    void lowOwnEntryShareFiresBelowHalfOfTheMedian() {
        // 自主占比 0.5%（中位数 2% 的一半是 1%）
        assertThat(codes(ChannelDiagnoser.diagnoseDomain(domain(400, 2, 56, 40, 20, 100, 9), null, DOMAIN_BENCH)))
            .contains("LOW_OWN_ENTRY_SHARE");
        // 刚好 1.0% 不触发
        assertThat(codes(ChannelDiagnoser.diagnoseDomain(domain(400, 4, 56, 40, 20, 100, 9), null, DOMAIN_BENCH)))
            .doesNotContain("LOW_OWN_ENTRY_SHARE");
    }

    @Test
    void highCompositeDependencyFiresAboveOnePointFiveTimesTheMedian() {
        // 依赖度 30% > 14% × 1.5 = 21%
        assertThat(codes(ChannelDiagnoser.diagnoseDomain(domain(200, 6, 60, 24, 20, 100, 9), null, DOMAIN_BENCH)))
            .contains("HIGH_COMPOSITE_DEPENDENCY");
    }

    @Test
    void lowOwnCtrNeedsEnoughImpressions() {
        // 6 / 100 = 6% < 12% × 0.7 = 8.4%
        assertThat(codes(ChannelDiagnoser.diagnoseDomain(domain(200, 6, 28, 24, 20, 100, 9), null, DOMAIN_BENCH)))
            .contains("LOW_OWN_CTR");
        // 曝光只有 40，不下结论
        assertThat(codes(ChannelDiagnoser.diagnoseDomain(domain(200, 2, 28, 24, 20, 40, 9), null, DOMAIN_BENCH)))
            .doesNotContain("LOW_OWN_CTR");
    }

    @Test
    void lowInteractionAndHighConcentrationAndLowSupply() {
        List<String> result = codes(ChannelDiagnoser.diagnoseDomain(domain(200, 6, 28, 8, 100, 40, 3), null, DOMAIN_BENCH));

        assertThat(result).contains("LOW_INTERACTION", "HIGH_CONCENTRATION", "LOW_SUPPLY");
    }

    @Test
    void smallChannelsGetNoVerdictOnShareBasedRules() {
        // PV 只有 20，不到 30
        List<String> result = codes(ChannelDiagnoser.diagnoseDomain(domain(20, 0, 8, 0, 15, 10, 9), null, DOMAIN_BENCH));

        assertThat(result).isEmpty();
    }

    @Test
    void pvDropIsWarnBelowEightyPercentAndCriticalBelowFifty() {
        ChannelPeriod previous = domain(200, 6, 28, 24, 20, 100, 9);

        Diagnosis warn = ChannelDiagnoser.diagnoseDomain(domain(150, 5, 21, 18, 15, 100, 9), previous, DOMAIN_BENCH)
            .stream().filter(d -> d.code().equals("PV_DROP")).findFirst().orElseThrow();
        Diagnosis critical = ChannelDiagnoser.diagnoseDomain(domain(90, 3, 12, 9, 9, 100, 9), previous, DOMAIN_BENCH)
            .stream().filter(d -> d.code().equals("PV_DROP")).findFirst().orElseThrow();

        assertThat(warn.severity()).isEqualTo(Diagnosis.WARN);
        assertThat(warn.message()).contains("200 → 150");
        assertThat(critical.severity()).isEqualTo(Diagnosis.CRITICAL);
        assertThat(codes(ChannelDiagnoser.diagnoseDomain(domain(170, 5, 24, 20, 17, 100, 9), previous, DOMAIN_BENCH)))
            .doesNotContain("PV_DROP");
    }

    @Test
    void noDropVerdictWhenThePreviousPeriodIsTooSmall() {
        ChannelPeriod previous = domain(20, 1, 3, 2, 2, 10, 9);

        assertThat(codes(ChannelDiagnoser.diagnoseDomain(domain(5, 1, 3, 2, 2, 10, 9), previous, DOMAIN_BENCH)))
            .doesNotContain("PV_DROP");
    }

    // ---- 综合频道 ----

    private static ChannelPeriod composite(long impressions, long clicks, int domainsCovered) {
        return new ChannelPeriod(new DistributionMetrics(impressions, clicks,
            impressions == 0 ? null : clicks / (double) impressions, impressions, clicks, 50.0),
            null, null, new CompositeCoverage(domainsCovered, 0.2));
    }

    private static final Map<String, Double> COMPOSITE_BENCH = Map.of(Medians.CTR, 0.06, Medians.DOMAINS_WITH_CONTENT, 20.0);

    @Test
    void aHealthyCompositeChannelHasNoDiagnoses() {
        assertThat(ChannelDiagnoser.diagnoseComposite(composite(1000, 60, 15), composite(1000, 60, 15), COMPOSITE_BENCH))
            .isEmpty();
    }

    @Test
    void compositeLowCtrDropAndCoverage() {
        // CTR 3% < 6% × 0.7；点击 30 vs 上期 100 → 下降 70%；覆盖 5 < 20 / 2
        List<Diagnosis> result = ChannelDiagnoser.diagnoseComposite(
            composite(1000, 30, 5), composite(1000, 100, 15), COMPOSITE_BENCH);

        assertThat(codes(result)).containsExactlyInAnyOrder("LOW_CTR", "CLICK_DROP", "LOW_COVERAGE");
        assertThat(result.stream().filter(d -> d.code().equals("CLICK_DROP")).findFirst().orElseThrow().severity())
            .isEqualTo(Diagnosis.CRITICAL);
    }

    @Test
    void compositeRulesStaySilentWithTooLittleData() {
        assertThat(ChannelDiagnoser.diagnoseComposite(composite(40, 1, 1), composite(20, 10, 1), COMPOSITE_BENCH)).isEmpty();
    }

    // ---- 中位数 ----

    @Test
    void medianOfOddAndEvenCountsAndEmpty() {
        assertThat(Medians.median(List.of(3.0, 1.0, 2.0))).isEqualTo(2.0);
        assertThat(Medians.median(List.of(4.0, 1.0, 2.0, 3.0))).isEqualTo(2.5);
        assertThat(Medians.median(List.of())).isNull();
    }

    @Test
    void domainMediansSkipChannelsWithoutData() {
        ChannelPeriod empty = new ChannelPeriod(DistributionMetrics.EMPTY, ContentMetrics.EMPTY, SupplyMetrics.EMPTY, null);
        Map<String, Double> medians = Medians.domain(List.of(
            domain(200, 4, 28, 24, 20, 100, 8), domain(200, 8, 28, 24, 20, 100, 10), empty));

        assertThat(medians.get(Medians.OWN_ENTRY_SHARE)).isEqualTo(0.03);
        assertThat(medians.get(Medians.CONTENTS)).isEqualTo(9.0);
    }

    @Test
    void domainMediansAreAbsentWhenNobodyHasData() {
        ChannelPeriod empty = new ChannelPeriod(DistributionMetrics.EMPTY, ContentMetrics.EMPTY, SupplyMetrics.EMPTY, null);

        assertThat(Medians.domain(List.of(empty, empty))).isEmpty();
    }

    @Test
    void compositeMediansCarryTheNumberOfDomainsWithContent() {
        Map<String, Double> medians = Medians.composite(List.of(composite(100, 6, 3), composite(100, 4, 3)), 17);

        assertThat(medians.get(Medians.CTR)).isEqualTo(0.05);
        assertThat(medians.get(Medians.DOMAINS_WITH_CONTENT)).isEqualTo(17.0);
    }
}
