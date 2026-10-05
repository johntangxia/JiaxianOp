package com.jiaxian.analytics.drill;

import com.jiaxian.analytics.pv.Granularity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api")
public class DrillController {

    private final DrillService service;

    public DrillController(DrillService service) {
        this.service = service;
    }

    /** 在这个领域频道发过内容的账号及其指标（只统计该频道里的内容）。综合频道没有内容，返回 400。 */
    @GetMapping("/drill/channels/{id}/accounts")
    public ChannelAccounts channelAccounts(
        @PathVariable long id,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.channelAccounts(id, from, to);
    }

    @GetMapping("/drill/accounts/{id}")
    public AccountOverview account(
        @PathVariable long id,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue = "DAY") Granularity granularity) {
        return service.account(id, from, to, granularity);
    }

    /** 博主按"作为作者"统计：合著内容每位作者都记完整的 PV 和互动。 */
    @GetMapping("/drill/bloggers/{id}")
    public BloggerOverview blogger(
        @PathVariable long id,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue = "DAY") Granularity granularity) {
        return service.blogger(id, from, to, granularity);
    }

    @GetMapping("/drill/contents/{id}")
    public ContentOverview content(
        @PathVariable long id,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue = "DAY") Granularity granularity) {
        return service.content(id, from, to, granularity);
    }

    /** 2–5 个同类对象的并排对比；type 为大写枚举 CHANNEL / ACCOUNT / BLOGGER，ids 逗号分隔。 */
    @GetMapping("/compare")
    public Comparison compare(
        @RequestParam SubjectType type,
        @RequestParam List<Long> ids,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue = "DAY") Granularity granularity) {
        return service.compare(type, ids, from, to, granularity);
    }
}
