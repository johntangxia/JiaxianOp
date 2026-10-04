package com.jiaxian.analytics.dimension;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 前端筛选/选择器用的只读数据：频道、账号、员工选项和 PV 数据范围。 */
@RestController
@RequestMapping("/api")
public class DimensionController {

    private final DimensionService service;

    public DimensionController(DimensionService service) {
        this.service = service;
    }

    @GetMapping("/dimensions/channels")
    public List<ChannelOption> channels() {
        return service.channels();
    }

    /** keyword 省略表示不过滤；limit 范围 1~100，默认 20。 */
    @GetMapping("/dimensions/accounts")
    public List<AccountOption> accounts(@RequestParam(required = false) String keyword,
                                        @RequestParam(defaultValue = "20") int limit) {
        return service.accounts(keyword, limit);
    }

    @GetMapping("/dimensions/employees")
    public List<EmployeeOption> employees(@RequestParam(required = false) String keyword,
                                          @RequestParam(defaultValue = "20") int limit) {
        return service.employees(keyword, limit);
    }

    /** PV 汇总表里有数据的日期范围，没有数据时 from/to 都是 null。 */
    @GetMapping("/meta/pv-range")
    public PvRange pvRange() {
        return service.pvRange();
    }
}
