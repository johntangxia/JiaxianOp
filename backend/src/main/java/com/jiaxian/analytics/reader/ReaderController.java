package com.jiaxian.analytics.reader;

import com.jiaxian.analytics.pv.Granularity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jiaxian.analytics.export.CsvWriter;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/readers")
public class ReaderController {

    private final ReaderService service;

    public ReaderController(ReaderService service) {
        this.service = service;
    }

    /** 默认数据范围：登录/发帖行为的起止日（模拟数据里终点就是模拟的"现在"）；没有数据时都是 null。 */
    @GetMapping("/range")
    public DataRange range() {
        return service.range();
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

    /** 沉默读者名单导出 CSV（全部，不分页）：列和名单一致。 */
    @GetMapping("/lurkers/export")
    public ResponseEntity<byte[]> lurkersExport(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) String dept) {
        List<List<?>> rows = new ArrayList<>();
        for (ReaderQueryRepository.Lurker lurker : service.lurkersExport(from, to, dept)) {
            rows.add(Arrays.asList(lurker.employeeId(), lurker.name(), lurker.dept(), lurker.joinDate(), lurker.views(),
                lurker.activeDays(), lurker.lastActive()));
        }
        return CsvWriter.download("沉默读者_" + from + "_" + to + ".csv",
            List.of("员工ID", "姓名", "部门", "加入社区", "浏览次数", "活跃天数", "最近活跃"), rows);
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
