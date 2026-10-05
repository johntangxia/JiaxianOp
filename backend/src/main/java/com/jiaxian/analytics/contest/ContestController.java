package com.jiaxian.analytics.contest;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/contest")
public class ContestController {

    private final ContestService service;

    public ContestController(ContestService service) {
        this.service = service;
    }

    /** 可选周期，最新的在前，最多 52 个。type 为大写枚举 WEEK / MONTH。 */
    @GetMapping("/periods")
    public List<PeriodOption> periods(@RequestParam ContestType type) {
        return service.periods(type);
    }

    /** 一个周期的领域榜、综合榜和进步榜；periodStart 必须是周一（周榜）或每月 1 号（月榜）。 */
    @GetMapping("/boards")
    public ContestBoards boards(
        @RequestParam ContestType type,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart) {
        return service.boards(type, periodStart);
    }
}
