package com.jiaxian.analytics.pv;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯逻辑：在连续的周期序列上计算环比、同比和预测。
 *
 * <p>buckets 应当包含请求范围之前多取的一年，这样范围内最早的点也能算出同比；
 * 只有 periodStart 不早于 visibleFrom 的点会放进结果，预测则基于整个序列里已完成的周期。
 */
public final class PvTrendAnalyzer {

    public record Analysis(List<PvPoint> points, List<ForecastPoint> forecast) {
    }

    private PvTrendAnalyzer() {
    }

    public static Analysis analyze(List<PvBucket> buckets, Granularity granularity,
                                   LocalDate visibleFrom, int forecastPeriods) {
        Map<LocalDate, Long> pvByStart = new HashMap<>();
        for (PvBucket bucket : buckets) {
            pvByStart.put(bucket.periodStart(), bucket.pv());
        }

        List<PvPoint> points = new ArrayList<>();
        for (int i = 0; i < buckets.size(); i++) {
            PvBucket bucket = buckets.get(i);
            if (bucket.periodStart().isBefore(visibleFrom)) {
                continue;
            }
            Double mom = i == 0 ? null : rate(bucket.pv(), buckets.get(i - 1).pv());
            Long lastYear = pvByStart.get(granularity.sameLastYear(bucket.periodStart()));
            Double yoy = lastYear == null ? null : rate(bucket.pv(), lastYear);
            points.add(new PvPoint(bucket.periodStart(), bucket.pv(), mom, yoy, bucket.complete()));
        }
        return new Analysis(points, forecast(buckets, granularity, forecastPeriods));
    }

    private static Double rate(long current, long base) {
        return base == 0 ? null : (current - base) / (double) base;
    }

    private static List<ForecastPoint> forecast(List<PvBucket> buckets, Granularity granularity, int periods) {
        List<PvBucket> complete = buckets.stream().filter(PvBucket::complete).toList();
        int window = granularity.forecastWindow();
        int size = complete.size();
        if (periods <= 0 || size < window) {
            return List.of();
        }

        double ma = mean(complete, size - window, size);
        double slope = size >= 2 * window ? (ma - mean(complete, size - 2 * window, size - window)) / window : 0;
        double sigma = sampleStdDev(complete, size - window, size, ma);

        List<ForecastPoint> forecast = new ArrayList<>();
        LocalDate periodStart = granularity.nextPeriodStart(complete.get(size - 1).periodStart());
        for (int k = 1; k <= periods; k++) {
            double value = Math.max(0, ma + slope * k);
            forecast.add(new ForecastPoint(periodStart, value, Math.max(0, value - sigma), value + sigma));
            periodStart = granularity.nextPeriodStart(periodStart);
        }
        return forecast;
    }

    private static double mean(List<PvBucket> buckets, int fromInclusive, int toExclusive) {
        double sum = 0;
        for (int i = fromInclusive; i < toExclusive; i++) {
            sum += buckets.get(i).pv();
        }
        return sum / (toExclusive - fromInclusive);
    }

    private static double sampleStdDev(List<PvBucket> buckets, int fromInclusive, int toExclusive, double mean) {
        int n = toExclusive - fromInclusive;
        if (n < 2) {
            return 0;
        }
        double squares = 0;
        for (int i = fromInclusive; i < toExclusive; i++) {
            double diff = buckets.get(i).pv() - mean;
            squares += diff * diff;
        }
        return Math.sqrt(squares / (n - 1));
    }
}
