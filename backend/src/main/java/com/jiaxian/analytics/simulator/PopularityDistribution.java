package com.jiaxian.analytics.simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class PopularityDistribution {

    private static final double SHAPE = 1.2;
    private final Random random;

    public PopularityDistribution(Random random) {
        this.random = random;
    }

    /**
     * 用帕累托分布逆变换采样，返回归一化到 (0, 1] 的热度权重列表，
     * 数值越大代表越"热"，符合内容热度二八效应的分布特征。
     */
    public List<Double> generateWeights(int count) {
        List<Double> raw = new ArrayList<>(count);
        double max = 0.0;
        for (int i = 0; i < count; i++) {
            double u = 1.0 - random.nextDouble();
            double sample = Math.pow(u, -1.0 / SHAPE);
            raw.add(sample);
            max = Math.max(max, sample);
        }

        List<Double> normalized = new ArrayList<>(count);
        for (double v : raw) {
            normalized.add(v / max);
        }
        return normalized;
    }
}
