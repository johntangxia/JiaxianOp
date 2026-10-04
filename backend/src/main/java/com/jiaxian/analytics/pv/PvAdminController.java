package com.jiaxian.analytics.pv;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin/analytics/pv")
public class PvAdminController {

    public record RebuildResult(LocalDate from, LocalDate to, int rowsWritten) {
    }

    private final PvSummaryService summaryService;

    public PvAdminController(PvSummaryService summaryService) {
        this.summaryService = summaryService;
    }

    /** from/to 都可省略；全省略表示重算全部历史（首次灌数据用）。 */
    @PostMapping("/rebuild")
    public RebuildResult rebuild(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return new RebuildResult(from, to, summaryService.rebuild(from, to));
    }
}
