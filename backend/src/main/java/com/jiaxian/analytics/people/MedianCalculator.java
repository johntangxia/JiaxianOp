package com.jiaxian.analytics.people;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 纯逻辑：中位数（偶数个取中间两个的平均），没有数据为 null。 */
public final class MedianCalculator {

    private MedianCalculator() {
    }

    public static Double median(List<? extends Number> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>();
        values.forEach(v -> sorted.add(v.doubleValue()));
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
}
