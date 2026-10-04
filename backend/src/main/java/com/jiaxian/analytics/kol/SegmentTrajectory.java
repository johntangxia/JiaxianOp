package com.jiaxian.analytics.kol;

import java.util.ArrayList;
import java.util.List;

/** 纯逻辑：从按统计日升序的历史里找出标签变化的位置（变化发生在后一期）。 */
public final class SegmentTrajectory {

    private SegmentTrajectory() {
    }

    public static List<SegmentChange> changes(List<HistoryPoint> history) {
        List<SegmentChange> changes = new ArrayList<>();
        for (int i = 1; i < history.size(); i++) {
            HistoryPoint previous = history.get(i - 1);
            HistoryPoint current = history.get(i);
            if (previous.segment() != current.segment()) {
                changes.add(new SegmentChange(current.asOf(), previous.segment(), current.segment()));
            }
        }
        return changes;
    }
}
