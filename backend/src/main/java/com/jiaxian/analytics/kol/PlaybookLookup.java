package com.jiaxian.analytics.kol;

import java.util.List;

/** 一次性载入全部文案后在内存里查找：先精确匹配 (分层, 预警状态)，再回退到 (分层, NO_ALERT)，都没有则返回 null。 */
public final class PlaybookLookup {

    private final List<PlaybookEntry> entries;

    public PlaybookLookup(List<PlaybookEntry> entries) {
        this.entries = entries;
    }

    public String find(Segment segment, boolean churnAlert) {
        PlaybookAlertStatus wanted = churnAlert ? PlaybookAlertStatus.ALERT : PlaybookAlertStatus.NO_ALERT;
        String fallback = null;
        for (PlaybookEntry entry : entries) {
            if (entry.segmentLabel() != segment) {
                continue;
            }
            if (entry.alertStatus() == wanted) {
                return entry.recommendationText();
            }
            if (entry.alertStatus() == PlaybookAlertStatus.NO_ALERT) {
                fallback = entry.recommendationText();
            }
        }
        return fallback;
    }
}
