package com.jiaxian.analytics.leaderboard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/leaderboard")
public class LeaderboardController {

    private final LeaderboardService service;

    public LeaderboardController(LeaderboardService service) {
        this.service = service;
    }

    /**
     * 月榜/年榜。type 只接受小写 month / year；period 省略表示当前周期；
     * 进行中的周期实时计算，已结束的周期读快照（缺快照时回退实时计算）。
     */
    @GetMapping("/{type}")
    public Leaderboard leaderboard(@PathVariable String type,
                                   @RequestParam(required = false) String period,
                                   @RequestParam(defaultValue = "50") int limit) {
        return service.leaderboard(PeriodType.fromPath(type), period, limit);
    }
}
