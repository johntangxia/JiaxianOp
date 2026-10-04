package com.jiaxian.analytics.kol;

import com.jiaxian.analytics.pv.Granularity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 个人博主详情页：基本信息 + 分层 + 历史轨迹、活跃时间轴、积分构成趋势。员工不存在返回 404。 */
@RestController
@RequestMapping("/api/bloggers/{id}")
public class BloggerProfileController {

    private final BloggerProfileService service;

    public BloggerProfileController(BloggerProfileService service) {
        this.service = service;
    }

    /** weeks：历史轨迹覆盖最近几次每周重算，默认 26，范围 1~104。 */
    @GetMapping("/profile")
    public BloggerProfile profile(@PathVariable long id, @RequestParam(defaultValue = "26") int weeks) {
        return service.profile(id, weeks);
    }

    /** 活跃时间轴：各周期的发帖/评论/登录次数。granularity 默认 WEEK，to 默认今天，from 默认按粒度往前推。 */
    @GetMapping("/activity")
    public ActivitySeries activity(
        @PathVariable long id,
        @RequestParam(required = false) Granularity granularity,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.activity(id, granularity, from, to);
    }

    /** 积分构成趋势：各周期的主动分、被动分（堆叠图）。参数规则同 activity。 */
    @GetMapping("/points-trend")
    public PointsTrend pointsTrend(
        @PathVariable long id,
        @RequestParam(required = false) Granularity granularity,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.pointsTrend(id, granularity, from, to);
    }
}
