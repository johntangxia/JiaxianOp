package com.jiaxian.analytics.kol;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin/analytics/kol")
public class KolAdminController {

    private final KolRebuildService rebuildService;
    private final Clock clock;

    public KolAdminController(KolRebuildService rebuildService, Clock clock) {
        this.rebuildService = rebuildService;
        this.clock = clock;
    }

    /** asOf 默认今天，weeks 默认 1（最多 52）；weeks > 1 时从旧到新补最近几周，用于补历史。 */
    @PostMapping("/rebuild")
    public List<KolRebuildResult> rebuild(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
        @RequestParam(defaultValue = "1") int weeks) {
        return rebuildService.rebuildWeeks(asOf != null ? asOf : LocalDate.now(clock), weeks);
    }
}
