package com.jiaxian.analytics.leaderboard;

import java.util.List;

public record Leaderboard(PeriodType periodType, String periodValue, Status status, Source source,
                          List<LeaderboardEntry> entries) {

    /** IN_PROGRESS：周期还没结束；ARCHIVED：已结束。 */
    public enum Status { IN_PROGRESS, ARCHIVED }

    /** LIVE：实时对积分流水求和；SNAPSHOT：读已归档的快照。 */
    public enum Source { LIVE, SNAPSHOT }
}
