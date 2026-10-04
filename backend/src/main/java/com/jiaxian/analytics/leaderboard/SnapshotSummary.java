package com.jiaxian.analytics.leaderboard;

/** rebuildAllEnded 的结果：重算了多少个月、多少个年，共写入多少行。 */
public record SnapshotSummary(int months, int years, int rowsWritten) {
}
