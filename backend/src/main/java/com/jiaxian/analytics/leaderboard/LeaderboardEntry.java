package com.jiaxian.analytics.leaderboard;

/** 榜单的一行。并列的员工名次相同（1,2,2,4）。 */
public record LeaderboardEntry(int rank, long employeeId, String name,
                               long points, long activePoints, long passivePoints) {
}
