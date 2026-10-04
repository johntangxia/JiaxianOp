package com.jiaxian.analytics.kol;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯逻辑：对名单内的人做五分位打分（1~5）。
 *
 * <p>F、M：分数 = 1 + (严格小于自己的人数 × 5) / 总人数（整除）；
 * R：天数越小越好，分数 = 1 + (严格大于自己的人数 × 5) / 总人数，没有任何活动记录视为无穷大。
 * 数值相同的人分数相同；全员数值相同则全员 1 分。
 */
public final class RfmScorer {

    private RfmScorer() {
    }

    public static Map<Long, RfmScores> score(List<BloggerMetrics> population) {
        int n = population.size();
        Map<Long, RfmScores> scores = new HashMap<>();
        for (BloggerMetrics person : population) {
            int fewerFrequency = 0;
            int fewerMonetary = 0;
            int laterRecency = 0;
            for (BloggerMetrics other : population) {
                if (other.frequency() < person.frequency()) {
                    fewerFrequency++;
                }
                if (other.monetary() < person.monetary()) {
                    fewerMonetary++;
                }
                if (recencyKey(other) > recencyKey(person)) {
                    laterRecency++;
                }
            }
            scores.put(person.employeeId(),
                new RfmScores(quintile(laterRecency, n), quintile(fewerFrequency, n), quintile(fewerMonetary, n)));
        }
        return scores;
    }

    private static int recencyKey(BloggerMetrics metrics) {
        return metrics.recencyDays() == null ? Integer.MAX_VALUE : metrics.recencyDays();
    }

    private static int quintile(int worseCount, int total) {
        return Math.min(5, 1 + (worseCount * 5) / total);
    }
}
