package com.jiaxian.analytics.people;

import java.util.Map;

/** 流转矩阵的一行：from 为空表示"上个时点还不是成员"。 */
public record TransitionRow(LifecycleStage from, Map<LifecycleStage, Long> to) {
}
