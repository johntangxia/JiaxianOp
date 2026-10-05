package com.jiaxian.analytics.people;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 纯逻辑：基尼系数（0 = 完全均匀，越接近 1 越集中）。没有数据或总和为 0 时为 null；只有一个值时为 0。 */
public final class GiniCalculator {

    private GiniCalculator() {
    }

    public static Double gini(List<Double> values) {
        int n = values.size();
        if (n == 0) {
            return null;
        }
        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
        if (sum <= 0) {
            return null;
        }
        if (n == 1) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        double weighted = 0;
        for (int i = 0; i < n; i++) {
            weighted += (i + 1) * sorted.get(i);
        }
        return 2 * weighted / (n * sum) - (n + 1.0) / n;
    }
}
