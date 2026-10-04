package com.jiaxian.analytics.kol;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/bloggers")
public class BloggerController {

    private final BloggerListService service;

    public BloggerController(BloggerListService service) {
        this.service = service;
    }

    /**
     * TOP N 博主分层名单。asOf 省略取最近一次重算；segment 为大写枚举；alert 为 true/false；
     * 响应里的 segmentCounts 是该统计日未筛选的各分层人数，total 是筛选后的总数。
     */
    @GetMapping("/top1000")
    public BloggerList top(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
        @RequestParam(required = false) Segment segment,
        @RequestParam(required = false) Boolean alert,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0") int offset) {
        return service.list(asOf, segment, alert, limit, offset);
    }

    /** 已有重算记录的统计日（新的在前，最多 104 个）。 */
    @GetMapping("/runs")
    public List<LocalDate> runs() {
        return service.runs();
    }
}
