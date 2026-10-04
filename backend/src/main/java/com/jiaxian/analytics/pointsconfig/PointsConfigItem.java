package com.jiaxian.analytics.pointsconfig;

import java.util.List;

/** 一个动作类型的权重配置：current 是生效日期最新的版本（引擎使用的那个），versions 新的在前。 */
public record PointsConfigItem(String actionType, String category, WeightVersion current,
                               List<WeightVersion> versions) {
}
