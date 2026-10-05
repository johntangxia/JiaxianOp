package com.jiaxian.analytics.channel;

import com.jiaxian.analytics.pv.Granularity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/channels")
public class ChannelCockpitController {

    private final ChannelCockpitService service;

    public ChannelCockpitController(ChannelCockpitService service) {
        this.service = service;
    }

    /** 综合频道和领域频道的驾驶舱：当期、上一等长周期、诊断和基准。区间两端含，最长 366 天。 */
    @GetMapping("/cockpit")
    public Cockpit cockpit(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.cockpit(from, to);
    }

    /** 单个频道的详情：行（含诊断）、趋势、构成和 Top 内容。granularity 默认 DAY（大写枚举）。 */
    @GetMapping("/{id}/detail")
    public ChannelDetail detail(
        @PathVariable long id,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue = "DAY") Granularity granularity) {
        return service.detail(id, from, to, granularity);
    }
}
