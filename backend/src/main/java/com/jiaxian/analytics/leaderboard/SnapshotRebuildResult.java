package com.jiaxian.analytics.leaderboard;

public record SnapshotRebuildResult(PeriodType periodType, String periodValue, int rowsWritten) {
}
