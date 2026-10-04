package com.jiaxian.analytics.kol;

import java.time.LocalDateTime;

/** 最近的一次发帖/评论/登录；refId 可为 null（比如登录）。 */
public record RecentEvent(LocalDateTime time, String type, Long refId) {
}
