package com.jiaxian.analytics.reader;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 一个新成员的首次行为：首次浏览、首次互动、首次发帖的时刻（没有为 null），以及加入后第 2 周是否仍活跃。 */
public record FunnelFacts(long employeeId, LocalDate joinDate, LocalDateTime firstView,
                          LocalDateTime firstInteraction, LocalDateTime firstPost, boolean activeInSecondWeek) {
}
