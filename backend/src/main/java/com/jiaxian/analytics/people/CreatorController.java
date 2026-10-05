package com.jiaxian.analytics.people;

import com.jiaxian.analytics.pv.Granularity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/creators")
public class CreatorController {

    private final CreatorService service;

    public CreatorController(CreatorService service) {
        this.service = service;
    }

    /** 创作者总览：周期序列、窗口汇总（对比上一个等长窗口）和按首次发帖月分组的留存（periods 默认 6，最多 12）。 */
    @GetMapping("/overview")
    public CreatorOverviewReport overview(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue = "MONTH") Granularity granularity,
        @RequestParam(required = false) Integer periods) {
        return service.overview(from, to, granularity, periods);
    }

    /** 创作者排行：sort 为 POSTS / PV / INTERACTION_RATE / COMPLETION / NEW_FOLLOWERS（默认 PV）；limit 1–100，默认 20。 */
    @GetMapping("/ranking")
    public CreatorRankingReport ranking(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) String sort,
        @RequestParam(required = false) String dept,
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) Integer offset) {
        return service.ranking(from, to, sort, dept, limit, offset);
    }

    /** 成长阶梯：截至 asOf（默认最近一次登录/发帖日）每个成员所处的最高阶段，以及到达率和耗时。 */
    @GetMapping("/ladder")
    public CreatorLadderReport ladder(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.ladder(asOf);
    }

    /** 产出集中度（按 PV）：前 1 名/前 5 名/前 10%/前 20%、基尼系数，以及个人账号与团队账号对比。 */
    @GetMapping("/concentration")
    public CreatorConcentrationReport concentration(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.concentration(from, to);
    }
}
