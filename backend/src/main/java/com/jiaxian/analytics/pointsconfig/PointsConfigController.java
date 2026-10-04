package com.jiaxian.analytics.pointsconfig;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 积分权重管理。修改只影响之后新产生的事件，已有积分流水不会重算（重新回填才会按新权重重新生成）。
 */
@RestController
@RequestMapping("/api/admin/points-config")
public class PointsConfigController {

    private final PointsConfigService service;

    public PointsConfigController(PointsConfigService service) {
        this.service = service;
    }

    @GetMapping
    public List<PointsConfigItem> list() {
        return service.list();
    }

    /** 为该动作新增"今天生效"的版本，今天已有版本则覆盖。 */
    @PutMapping("/{actionType}")
    public PointsConfigItem update(@PathVariable String actionType, @RequestBody WeightUpdateRequest request) {
        return service.update(actionType, request.weight());
    }

    @DeleteMapping("/versions/{id}")
    public ResponseEntity<Void> deleteVersion(@PathVariable long id) {
        service.deleteVersion(id);
        return ResponseEntity.noContent().build();
    }
}
