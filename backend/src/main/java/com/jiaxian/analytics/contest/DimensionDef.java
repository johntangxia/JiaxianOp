package com.jiaxian.analytics.contest;

import java.util.List;

/** 健康分的一个维度：权重，以及组成它的指标（维度得分 = 各指标百分位得分的平均）。 */
public record DimensionDef(String key, String label, double weight, List<String> metrics) {
}
