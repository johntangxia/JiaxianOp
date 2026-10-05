package com.jiaxian.analytics.growth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 纯逻辑：热点和潜力内容识别（基线都是"同频道、窗口内有浏览的内容"的中位数，免得大量没人看的内容把中位数拉成 0）。
 * 热点：近 3 天 PV ≥ 10 且 ≥ 基线 3 倍。潜力：近 30 天 PV ≥ 5、不超过同频道 PV 中位数，且互动率或读完率 ≥ 同频道中位数的 1.5 倍。
 */
public final class HotContentDetector {

    static final long HOT_MIN_PV = 10;
    static final double HOT_MULTIPLE = 3.0;
    static final long POTENTIAL_MIN_PV = 5;
    static final double POTENTIAL_QUALITY_MULTIPLE = 1.5;
    /** 互动率中位数很小（或为 0）时的下限，避免倍数被除成无穷大。 */
    static final double MIN_RATE_BASELINE = 0.01;

    private HotContentDetector() {
    }

    public static List<HotContent> detectHot(List<ContentStat> stats, int limit) {
        Map<Long, Double> baseline = medianByChannel(stats, s -> s.pv() > 0 ? (double) s.pv() : null);
        List<HotContent> hot = new ArrayList<>();
        for (ContentStat stat : stats) {
            double base = Math.max(1.0, baseline.getOrDefault(stat.channelId(), 1.0));
            if (stat.pv() >= HOT_MIN_PV && stat.pv() >= HOT_MULTIPLE * base) {
                hot.add(new HotContent(stat.contentId(), stat.channelId(), stat.pv(), stat.previousPv(), stat.pv() / base,
                    stat.pv() / (double) Math.max(1, stat.previousPv()), stat.interactionRate(), stat.completionRate()));
            }
        }
        hot.sort(Comparator.comparingDouble(HotContent::hotMultiple).reversed().thenComparingLong(HotContent::contentId));
        return hot.stream().limit(limit).toList();
    }

    public static List<PotentialContent> detectPotential(List<ContentStat> stats, int limit) {
        Map<Long, Double> pvMedian = medianByChannel(stats, s -> s.pv() > 0 ? (double) s.pv() : null);
        Map<Long, Double> rateMedian = medianByChannel(stats, ContentStat::interactionRate);
        Map<Long, Double> completionMedian = medianByChannel(stats, ContentStat::completionRate);
        List<PotentialContent> potential = new ArrayList<>();
        for (ContentStat stat : stats) {
            if (stat.pv() < POTENTIAL_MIN_PV || stat.pv() > pvMedian.getOrDefault(stat.channelId(), 0.0)) {
                continue;
            }
            double interactionMultiple = multiple(stat.interactionRate(), rateMedian.get(stat.channelId()));
            double completionMultiple = multiple(stat.completionRate(), completionMedian.get(stat.channelId()));
            double quality = Math.max(interactionMultiple, completionMultiple);
            if (quality >= POTENTIAL_QUALITY_MULTIPLE) {
                potential.add(new PotentialContent(stat.contentId(), stat.channelId(), stat.pv(), stat.interactionRate(),
                    stat.completionRate(), quality));
            }
        }
        potential.sort(Comparator.comparingDouble(PotentialContent::qualityMultiple).reversed()
            .thenComparingLong(PotentialContent::contentId));
        return potential.stream().limit(limit).toList();
    }

    private static double multiple(Double value, Double median) {
        if (value == null || median == null) {
            return 0;
        }
        return value / Math.max(median, MIN_RATE_BASELINE);
    }

    private static Map<Long, Double> medianByChannel(List<ContentStat> stats, Function<ContentStat, Double> extractor) {
        Map<Long, List<Double>> values = new HashMap<>();
        for (ContentStat stat : stats) {
            Double value = extractor.apply(stat);
            if (value != null) {
                values.computeIfAbsent(stat.channelId(), k -> new ArrayList<>()).add(value);
            }
        }
        Map<Long, Double> medians = new HashMap<>();
        values.forEach((channel, list) -> {
            list.sort(Double::compare);
            int n = list.size();
            medians.put(channel, n % 2 == 1 ? list.get(n / 2) : (list.get(n / 2 - 1) + list.get(n / 2)) / 2);
        });
        return medians;
    }
}
