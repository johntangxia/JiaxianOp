package com.jiaxian.analytics.contest;

/** 一个维度的得分（0–100）和它的权重。 */
public record DimensionScore(String key, String label, double weight, double score) {
}
