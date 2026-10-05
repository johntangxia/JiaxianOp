package com.jiaxian.analytics.people;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
@RequestMapping("/api/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    /** 生命周期：asOf 默认取最近一次登录/发帖日；含上一个时点（asOf−30）的对比和流转矩阵。 */
    @GetMapping("/lifecycle")
    public UserLifecycleReport lifecycle(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.lifecycle(asOf);
    }

    /** 价值分层：全员近 90 天的 RFM（核心/活跃/待唤醒/低活跃/沉睡）。 */
    @GetMapping("/value")
    public UserValueReport value(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.value(asOf);
    }

    /** 兴趣与跨领域：各部门成员近 days（7–180，默认 90）天的阅读去向。 */
    @GetMapping("/interests")
    public UserInterestReport interests(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
        @RequestParam(required = false) Integer days) {
        return service.interests(asOf, days);
    }

    /** 用户列表：可按生命周期阶段、价值分层、部门、姓名筛选；limit 1–100，默认 20。 */
    @GetMapping("/list")
    public UserListReport list(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
        @RequestParam(required = false) String stage,
        @RequestParam(required = false) String value,
        @RequestParam(required = false) String dept,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String sort,
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) Integer offset) {
        return service.list(asOf, stage, value, dept, q, sort, limit, offset);
    }

    /** 用户列表导出 CSV（全部符合条件的用户，不分页）：筛选和排序同用户列表接口。 */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
        @RequestParam(required = false) String stage,
        @RequestParam(required = false) String value,
        @RequestParam(required = false) String dept,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String sort) {
        UserService.Listing listing = service.export(asOf, stage, value, dept, q, sort);
        List<List<?>> rows = new ArrayList<>();
        for (UserListReport.UserRow row : listing.rows()) {
            rows.add(Arrays.asList(row.employeeId(), row.name(), row.dept(), row.joinDate(), row.stageLabel(),
                row.valueLabel(), row.lastActive(), row.activeDays(), row.views(), row.interactions(), row.posts()));
        }
        return CsvWriter.download("用户列表_" + listing.asOf() + ".csv",
            List.of("员工ID", "姓名", "部门", "加入社区", "生命周期", "价值分层", "最近活跃", "近90天活跃天数", "近90天浏览",
                "近90天互动", "近90天发帖"), rows);
    }

    /** 用户 360；员工不存在返回 404。 */
    @GetMapping("/{id}")
    public UserProfile profile(
        @PathVariable long id,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.profile(id, asOf);
    }
}
