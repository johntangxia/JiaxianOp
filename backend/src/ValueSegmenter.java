package com.jiaxian.analytics.people;

import com.jiaxian.analytics.contest.ContestScorer;

import java.util.ArrayList;
import java.util.List;

/** 纯逻辑：全员 RFM 打分（榜内百分位 → 1–5 分，没有数据得 1 分）并分层。 */
public final class ValueSegmenter {

    public static final int WINDOW_DAYS = 90;

    private ValueSegmenter() {
    }

    public static List<ValueScore> segment(List<ValueInput> inputs) {
        int n = inputs.size();
        double[] recency = new double[n];
        double[] frequency = new double[n];
        double[] monetary = new double[n];
        for (int i = 0; i < n; i++) {
            ValueInput input = inputs.get(i);
            // 越近越高；没有行为 = 0（得 1 分）
            recency[i] = input.daysSinceLast() == null ? 0 : Math.max(1, WINDOW_DAYS - input.daysSinceLast());
            frequency[i] = input.activeDays();
            monetary[i] = input.engagement();
        }
        double[] r = ContestScorer.percentiles(recency);
        double[] f = ContestScorer.percentiles(frequency);
        double[] m = ContestScorer.percentiles(monetary);
        List<ValueScore> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int rs = toScore(r[i]);
            int fs = toScore(f[i]);
            int ms = toScore(m[i]);
            result.add(new ValueScore(inputs.get(i).employeeId(), rs, fs, ms, classify(rs, fs, ms)));
        }
        return result;
    }

    /** 百分位 [0,20) → 1 … [80,100] → 5。 */
    static int toScore(double percentile) {
        return Math.min(5, 1 + (int) Math.floor(percentile / 20));
    }

    static ValueSegment classify(int r, int f, int m) {
        if (m >= 4 && r >= 4) {
            return ValueSegment.CORE;
        }
        if (m >= 3 && r >= 3) {
            return ValueSegment.ACTIVE;
        }
        if (r <= 2 && (f >= 3 || m >= 3)) {
            return ValueSegment.TO_WAKE;
        }
        if (r >= 3 && m <= 2) {
            return ValueSegment.LOW_ACTIVE;
        }
        return ValueSegment.SLEEPING;
    }
}
