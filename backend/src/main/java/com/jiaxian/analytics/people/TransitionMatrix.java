package com.jiaxian.analytics.people;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 纯逻辑：上一个时点的阶段 → 现在的阶段的人数矩阵。 */
public final class TransitionMatrix {

    private TransitionMatrix() {
    }

    /**
     * @param previous 上个时点各成员的阶段，不在其中的当时还不是成员
     * @param current  现在各成员的阶段
     * @return 每个阶段一行（人数可为 0），最后一行是"此前非成员"
     */
    public static List<TransitionRow> build(Map<Long, LifecycleStage> previous, Map<Long, LifecycleStage> current) {
        Map<LifecycleStage, Map<LifecycleStage, Long>> rows = new EnumMap<>(LifecycleStage.class);
        for (LifecycleStage from : LifecycleStage.values()) {
            rows.put(from, zeros());
        }
        Map<LifecycleStage, Long> fresh = zeros();
        current.forEach((id, now) -> {
            LifecycleStage before = previous.get(id);
            Map<LifecycleStage, Long> row = before == null ? fresh : rows.get(before);
            row.merge(now, 1L, Long::sum);
        });
        List<TransitionRow> result = new ArrayList<>();
        rows.forEach((from, to) -> result.add(new TransitionRow(from, to)));
        result.add(new TransitionRow(null, fresh));
        return result;
    }

    private static Map<LifecycleStage, Long> zeros() {
        Map<LifecycleStage, Long> map = new EnumMap<>(LifecycleStage.class);
        for (LifecycleStage stage : LifecycleStage.values()) {
            map.put(stage, 0L);
        }
        return map;
    }
}
