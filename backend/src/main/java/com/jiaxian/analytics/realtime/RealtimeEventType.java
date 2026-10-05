package com.jiaxian.analytics.realtime;

public enum RealtimeEventType {
    VIEW, LIKE, SHARE, FAVORITE, COMMENT, COMMENT_LIKE, LOGIN,
    /** 在某个入口看到了内容但没有点击（点击的曝光随 VIEW 事件一起落库）。 */
    IMPRESSION
}
