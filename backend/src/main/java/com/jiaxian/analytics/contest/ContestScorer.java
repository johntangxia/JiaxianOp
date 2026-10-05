package com.jiaxian.analytics.contest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯逻辑：榜内百分位打分。
 * <ul>
 *   <li>指标得分 = (比它小的个数 + 0.5 × (并列个数 − 1)) / (榜内频道数 − 1) × 100，第一名 100、最后一名 0，并列取平均；</li>
 *   <li>指标值为 0（或缺失）的频道该项一律得 0 分：没有数据不能靠并列平均白拿分；因此一个指标榜内全为 0 时所有频道该项得 0；
 *       榜里只有一个频道时，有数据得 100、没数据得 0；</li>
 *   <li>维度得分 = 组成它的各指标得分的平均；健康分 = Σ 维度权重 × 维度得分。</li>
 * </ul>
 */
public final class ContestScorer {

    private ContestScorer() {
    }

    /** 结果与 candidates 顺序一致。 */
    public static List<ScoredChannel> score(List<Candidate> candidates, List<DimensionDef> dimensions) {
        Map<String, double[]> metricScores = new HashMap<>();
        for (DimensionDef dimension : dimensions) {
            for (String metric : dimension.metrics()) {
                metricScores.computeIfAbsent(metric, key -> percentiles(candidates.stream()
                    .mapToDouble(c -> c.metric(key)).toArray()));
            }
        }

        List<ScoredChannel> result = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            List<DimensionScore> scores = new ArrayList<>();
            double total = 0;
            for (DimensionDef dimension : dimensions) {
                double sum = 0;
                for (String metric : dimension.metrics()) {
                    sum += metricScores.get(metric)[i];
                }
                double dimensionScore = sum / dimension.metrics().size();
                scores.add(new DimensionScore(dimension.key(), dimension.label(), dimension.weight(), dimensionScore));
                total += dimension.weight() * dimensionScore;
            }
            result.add(new ScoredChannel(candidates.get(i).channelId(), total, scores));
        }
        return result;
    }

    public static double[] percentiles(double[] values) {
        int n = values.length;
        double[] scores = new double[n];
        boolean anyPositive = false;
        for (double value : values) {
            anyPositive |= value > 0;
        }
        if (!anyPositive) {
            return scores;   // 全 0：都是 0 分
        }
        if (n == 1) {
            scores[0] = 100;
            return scores;
        }
        for (int i = 0; i < n; i++) {
            if (values[i] <= 0) {
                continue;   // 没有数据：0 分
            }
            int smaller = 0;
            int equal = 0;
            for (double other : values) {
                if (other < values[i]) {
                    smaller++;
                } else if (other == values[i]) {
                    equal++;
                }
            }
            scores[i] = (smaller + 0.5 * (equal - 1)) / (n - 1) * 100;
        }
        return scores;
    }

    /** 标准竞赛排名：按健康分从高到低，分数相同名次相同（1、2、2、4）；键是频道 id。 */
    public static Map<Long, Integer> ranks(List<ScoredChannel> scored) {
        List<ScoredChannel> sorted = new ArrayList<>(scored);
        sorted.sort((a, b) -> b.total() != a.total() ? Double.compare(b.total(), a.total())
            : Long.compare(a.channelId(), b.channelId()));
        Map<Long, Integer> ranks = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            boolean tiedWithPrevious = i > 0 && sorted.get(i).total() == sorted.get(i - 1).total();
            ranks.put(sorted.get(i).channelId(), tiedWithPrevious ? ranks.get(sorted.get(i - 1).channelId()) : i + 1);
        }
        return ranks;
    }
}
