package com.jiaxian.analytics.growth;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 增长闭环。asOf 省略时取最近一次登录/发帖的日期；/api/admin 下的写接口和项目里其他管理接口一样没有鉴权（只能内网部署）。 */
@RestController
@RequestMapping("/api")
public class GrowthController {

    private final GrowthService service;

    public GrowthController(GrowthService service) {
        this.service = service;
    }

    /** 部门渗透率；days 7–90，默认 30，对比上一个等长窗口。 */
    @GetMapping("/growth/departments")
    public DepartmentReport departments(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
        @RequestParam(required = false) Integer days) {
        return service.departments(asOf, days);
    }

    /** 热点内容（近 3 天）和潜力内容（近 30 天）；limit 1–100，默认 20。 */
    @GetMapping("/growth/hot-contents")
    public HotContentReport hotContents(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
        @RequestParam(required = false) Integer limit) {
        return service.hotContents(asOf, limit);
    }

    @GetMapping("/growth/alerts")
    public AlertList alerts(@RequestParam(required = false) AlertStatus status, @RequestParam(required = false) String kind,
                            @RequestParam(required = false) GrowthEntityType entityType,
                            @RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset) {
        return service.alerts(status, kind, entityType, limit, offset);
    }

    /** 按规则重算告警：幂等，已有状态保留，已关闭的不重开。 */
    @PostMapping("/admin/growth/alerts/rebuild")
    public AlertRebuildResult rebuildAlerts(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.rebuildAlerts(asOf);
    }

    @PutMapping("/admin/growth/alerts/{id}/status")
    public GrowthAlert updateAlertStatus(@PathVariable long id, @RequestBody AlertStatusRequest request) {
        return service.updateAlertStatus(id, request.status());
    }

    @GetMapping("/growth/tasks")
    public TaskList tasks(@RequestParam(required = false) TaskStatus status, @RequestParam(required = false) Integer limit,
                          @RequestParam(required = false) Integer offset,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.tasks(status, limit, offset, asOf);
    }

    @GetMapping("/growth/tasks/{id}")
    public TaskDetail task(@PathVariable long id,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.task(id, asOf);
    }

    @PostMapping("/admin/growth/tasks")
    public ResponseEntity<TaskView> createTask(@RequestBody CreateTaskRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createTask(request));
    }

    @PutMapping("/admin/growth/tasks/{id}")
    public TaskView updateTask(@PathVariable long id, @RequestBody UpdateTaskRequest request) {
        return service.updateTask(id, request);
    }

    @DeleteMapping("/admin/growth/tasks/{id}")
    public ResponseEntity<Void> deleteTask(@PathVariable long id) {
        service.deleteTask(id);
        return ResponseEntity.noContent().build();
    }
}
