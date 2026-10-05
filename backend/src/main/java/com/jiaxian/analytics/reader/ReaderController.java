package com.jiaxian.analytics.reader;

import com.jiaxian.analytics.pv.Granularity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/readers")
public class ReaderController {

    private final ReaderService service;

    public ReaderController(ReaderService service) {
        this.service = service;
    }

    /** 活跃与增长：周期内去重活跃人数（日 = DAU、周 = WAU、月 = MAU）、新增成员、活跃率，以及截至 to 的汇总。 */
    @GetMapping("/activity")
    public ActivityReport activity(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue = "DAY") Granularity granularity) {
        return service.activity(from, to, granularity);
    }

    /** 新成员留存同期群（加入日期在 [from, to] 内）；granularity 为 WEEK 或 MONTH，periods 默认周 8 / 月 6，最多 12。 */
    @GetMapping("/retention")
    public RetentionReport retention(
        @RequestParam Granularity granularity,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) Integer periods) {
        return service.retention(granularity, from, to, periods);
    }

    /** 激活漏斗：加入日期在 [from, to] 内的新成员。 */
    @GetMapping("/funnel")
    public FunnelReport funnel(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.funnel(from, to);
    }

    /** 读者分层：创作者、互动者、沉默读者、仅登录、不活跃的人数和占比。 */
    @GetMapping("/segments")
    public SegmentReport segments(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.segments(from, to);
    }

    /** 沉默读者名单（展示姓名）：limit 1–100，默认 20；dept 可选。 */
    @GetMapping("/lurkers")
    public LurkerReport lurkers(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) String dept,
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) Integer offset) {
        return service.lurkers(from, to, dept, limit, offset);
    }
}
