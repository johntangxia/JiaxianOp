package com.jiaxian.analytics.leaderboard;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/analytics/leaderboard")
public class LeaderboardAdminController {

    private final LeaderboardSnapshotService snapshotService;

    public LeaderboardAdminController(LeaderboardSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    /**
     * 不带参数：重算所有已结束且有流水的月和年（首次回填完历史数据后调用一次）。
     * 带 periodType 和 periodValue：只重算那一期，周期必须已经结束。
     */
    @PostMapping("/snapshot")
    public Object snapshot(@RequestParam(required = false) PeriodType periodType,
                           @RequestParam(required = false) String periodValue) {
        if (periodType == null && periodValue == null) {
            return snapshotService.rebuildAllEnded();
        }
        if (periodType == null || periodValue == null) {
            throw new InvalidLeaderboardQueryException("periodType and periodValue must be given together");
        }
        return new SnapshotRebuildResult(periodType, periodValue, snapshotService.rebuild(periodType, periodValue));
    }
}
