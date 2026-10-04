package com.jiaxian.analytics.kol;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 建议文案库维护：(分层, 预警状态) 唯一，重复返回 409，不存在返回 404。 */
@RestController
@RequestMapping("/api/admin/playbook")
public class PlaybookController {

    private final PlaybookService service;

    public PlaybookController(PlaybookService service) {
        this.service = service;
    }

    @GetMapping
    public List<PlaybookEntry> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<PlaybookEntry> create(@RequestBody PlaybookRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    public PlaybookEntry update(@PathVariable long id, @RequestBody PlaybookRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
