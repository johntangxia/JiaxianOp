package com.jiaxian.analytics.pv;

import com.jiaxian.analytics.domain.ContentType;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/analytics/pv")
public class PvAnalyticsController {

    private final PvAnalyticsService service;

    public PvAnalyticsController(PvAnalyticsService service) {
        this.service = service;
    }

    /** PV 趋势（含环比、同比、预测）。枚举参数区分大小写（大写）。 */
    @GetMapping("/trend")
    public PvTrend trend(
        @RequestParam Granularity granularity,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) Long channelId,
        @RequestParam(required = false) Long accountId,
        @RequestParam(required = false) Long employeeId,
        @RequestParam(required = false) ContentType contentType,
        @RequestParam(defaultValue = "1") int forecastPeriods) {
        return service.trend(granularity, from, to,
            new PvFilter(channelId, accountId, employeeId, contentType), forecastPeriods);
    }

    /**
     * PV Top N。EMPLOYEE（博主）维度下，一篇内容有多位共同作者时每位作者都记完整 PV，
     * 所以所有博主的 PV 之和会大于全站总 PV。
     */
    @GetMapping("/top")
    public List<PvTopEntry> top(
        @RequestParam PvDimension dimension,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue = "10") int limit,
        @RequestParam(required = false) Long channelId,
        @RequestParam(required = false) Long accountId,
        @RequestParam(required = false) Long employeeId,
        @RequestParam(required = false) ContentType contentType) {
        return service.top(dimension, from, to,
            new PvFilter(channelId, accountId, employeeId, contentType), limit);
    }
}
