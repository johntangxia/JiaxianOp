package com.jiaxian.analytics.pv;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PvTrendAnalyzerTest {

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    /** 从 first 开始按粒度生成连续周期，前 completeCount 个标为已完成。 */
    private static List<PvBucket> series(Granularity g, LocalDate first, int completeCount, long... values) {
        List<PvBucket> list = new ArrayList<>();
        LocalDate start = first;
        for (int i = 0; i < values.length; i++) {
            list.add(new PvBucket(start, values[i], i < completeCount));
            start = g.nextPeriodStart(start);
        }
        return list;
    }

    // ---- 环比 / 同比 ----

    // 2025-01..2026-02 共 14 个月：100, 0, 50, 然后 9 个 10（2025-04..12），150（2026-01），75（2026-02）
    private static final long[] MONTHLY = {100, 0, 50, 10, 10, 10, 10, 10, 10, 10, 10, 10, 150, 75};

    @Test
    void momAndYoyAreComputedAgainstPreviousPeriodAndSamePeriodLastYear() {
        List<PvBucket> buckets = series(Granularity.MONTH, d(2025, 1, 1), 14, MONTHLY);

        PvTrendAnalyzer.Analysis analysis =
            PvTrendAnalyzer.analyze(buckets, Granularity.MONTH, d(2026, 1, 1), 0);

        assertThat(analysis.points()).hasSize(2);
        PvPoint jan = analysis.points().get(0);
        assertThat(jan.periodStart()).isEqualTo(d(2026, 1, 1));
        assertThat(jan.pv()).isEqualTo(150);
        assertThat(jan.momRate()).isCloseTo(14.0, within(1e-9));   // (150-10)/10
        assertThat(jan.yoyRate()).isCloseTo(0.5, within(1e-9));    // (150-100)/100
        PvPoint feb = analysis.points().get(1);
        assertThat(feb.momRate()).isCloseTo(-0.5, within(1e-9));   // (75-150)/150
        assertThat(feb.yoyRate()).isNull();                        // 去年同期是 0
    }

    @Test
    void firstPointAndZeroBaseHaveNoMomAndMissingLastYearHasNoYoy() {
        List<PvBucket> buckets = series(Granularity.MONTH, d(2025, 1, 1), 14, MONTHLY);

        PvTrendAnalyzer.Analysis analysis =
            PvTrendAnalyzer.analyze(buckets, Granularity.MONTH, d(2025, 1, 1), 0);

        assertThat(analysis.points()).hasSize(14);
        assertThat(analysis.points().get(0).momRate()).isNull();   // 第一个点
        assertThat(analysis.points().get(0).yoyRate()).isNull();   // 没有去年的数据
        assertThat(analysis.points().get(2).momRate()).isNull();   // 上一期是 0
        assertThat(analysis.points().get(3).momRate()).isCloseTo(-0.8, within(1e-9)); // (10-50)/50
    }

    // ---- 预测 ----

    @Test
    void forecastUsesMovingAveragePlusDrift() {
        // 窗口 3：已完成 10..60，2026-07 是进行中的周期（5）不参与。
        // ma = (40+50+60)/3 = 50；前一窗口 (10+20+30)/3 = 20；slope = (50-20)/3 = 10；
        // σ = 样本标准差(40,50,60) = sqrt((100+0+100)/2) = 10
        List<PvBucket> buckets = series(Granularity.MONTH, d(2026, 1, 1), 6, 10, 20, 30, 40, 50, 60, 5);

        List<ForecastPoint> forecast =
            PvTrendAnalyzer.analyze(buckets, Granularity.MONTH, d(2026, 1, 1), 2).forecast();

        assertThat(forecast).hasSize(2);
        assertForecast(forecast.get(0), d(2026, 7, 1), 60, 50, 70);   // k=1：从进行中的 7 月开始
        assertForecast(forecast.get(1), d(2026, 8, 1), 70, 60, 80);   // k=2
    }

    @Test
    void slopeIsZeroWhenFewerThanTwoWindowsAreComplete() {
        // 只有 3 个已完成点（N=3，不足 2N）：ma = 40，slope = 0，σ = 样本标准差(30,40,50) = 10
        List<PvBucket> buckets = series(Granularity.MONTH, d(2026, 1, 1), 3, 30, 40, 50);

        List<ForecastPoint> forecast =
            PvTrendAnalyzer.analyze(buckets, Granularity.MONTH, d(2026, 1, 1), 2).forecast();

        assertThat(forecast).hasSize(2);
        assertForecast(forecast.get(0), d(2026, 4, 1), 40, 30, 50);
        assertForecast(forecast.get(1), d(2026, 5, 1), 40, 30, 50);
    }

    @Test
    void noForecastWhenFewerThanWindowPointsAreComplete() {
        List<PvBucket> buckets = series(Granularity.MONTH, d(2026, 1, 1), 2, 30, 40, 7);

        assertThat(PvTrendAnalyzer.analyze(buckets, Granularity.MONTH, d(2026, 1, 1), 3).forecast()).isEmpty();
    }

    @Test
    void zeroForecastPeriodsYieldsNoForecast() {
        List<PvBucket> buckets = series(Granularity.MONTH, d(2026, 1, 1), 6, 10, 20, 30, 40, 50, 60);

        assertThat(PvTrendAnalyzer.analyze(buckets, Granularity.MONTH, d(2026, 1, 1), 0).forecast()).isEmpty();
    }

    @Test
    void forecastAndLowerBoundAreClampedAtZero() {
        // ma = (40+20+0)/3 = 20；前一窗口 (100+80+60)/3 = 80；slope = (20-80)/3 = -20
        // k=1：20-20 = 0；k=2：-20 → 0。σ = 样本标准差(40,20,0) = 20；下限 max(0, 0-20) = 0，上限 0+20
        List<PvBucket> buckets = series(Granularity.MONTH, d(2026, 1, 1), 6, 100, 80, 60, 40, 20, 0);

        List<ForecastPoint> forecast =
            PvTrendAnalyzer.analyze(buckets, Granularity.MONTH, d(2026, 1, 1), 2).forecast();

        assertForecast(forecast.get(0), d(2026, 7, 1), 0, 0, 20);
        assertForecast(forecast.get(1), d(2026, 8, 1), 0, 0, 20);
    }

    @Test
    void dayWindowIsSeven() {
        // 14 天 1..14：最近 7 天 8..14 均值 11；前 7 天 1..7 均值 4；slope = (11-4)/7 = 1
        // σ = sqrt(Σ(x-11)² / 6) = sqrt(28/6)
        List<PvBucket> buckets = series(Granularity.DAY, d(2026, 5, 1), 14,
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);

        List<ForecastPoint> forecast =
            PvTrendAnalyzer.analyze(buckets, Granularity.DAY, d(2026, 5, 1), 1).forecast();

        double sigma = Math.sqrt(28.0 / 6);
        assertForecast(forecast.get(0), d(2026, 5, 15), 12, 12 - sigma, 12 + sigma);
    }

    @Test
    void weekWindowIsFourAndYearWindowIsTwo() {
        // 周：窗口 4，已完成 4 个点全是 10 → ma=10, slope=0, σ=0
        List<ForecastPoint> weekly = PvTrendAnalyzer.analyze(
            series(Granularity.WEEK, d(2026, 3, 2), 4, 10, 10, 10, 10), Granularity.WEEK, d(2026, 3, 2), 1).forecast();
        assertForecast(weekly.get(0), d(2026, 3, 30), 10, 10, 10);

        // 年：窗口 2，ma = 150，slope = 0（不足 4 个点），σ = sqrt(((50)²+(50)²)/1) = sqrt(5000)
        List<ForecastPoint> yearly = PvTrendAnalyzer.analyze(
            series(Granularity.YEAR, d(2024, 1, 1), 2, 100, 200), Granularity.YEAR, d(2024, 1, 1), 1).forecast();
        double sigma = Math.sqrt(5000);
        assertForecast(yearly.get(0), d(2026, 1, 1), 150, 150 - sigma, 150 + sigma);

        // 年：只有 1 个已完成点，不够窗口 → 不预测
        assertThat(PvTrendAnalyzer.analyze(
            series(Granularity.YEAR, d(2025, 1, 1), 1, 100), Granularity.YEAR, d(2025, 1, 1), 1).forecast()).isEmpty();
    }

    private static void assertForecast(ForecastPoint actual, LocalDate periodStart,
                                       double value, double lower, double upper) {
        assertThat(actual.periodStart()).isEqualTo(periodStart);
        assertThat(actual.value()).isCloseTo(value, within(1e-9));
        assertThat(actual.lower()).isCloseTo(lower, within(1e-9));
        assertThat(actual.upper()).isCloseTo(upper, within(1e-9));
    }
}
