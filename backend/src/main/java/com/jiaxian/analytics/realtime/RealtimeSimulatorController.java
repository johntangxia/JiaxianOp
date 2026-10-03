package com.jiaxian.analytics.realtime;

import lombok.Getter;
import lombok.Setter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/simulator/realtime")
public class RealtimeSimulatorController {

    private static final int DEFAULT_INTERVAL_MILLIS = 2000;
    private static final int DEFAULT_EVENTS_PER_BATCH = 10;

    private final RealtimeEventProducer producer;

    public RealtimeSimulatorController(RealtimeEventProducer producer) {
        this.producer = producer;
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody(required = false) StartRequest request) {
        StartRequest effective = request == null ? new StartRequest() : request;
        int intervalMillis = effective.getIntervalMillis() == null
            ? DEFAULT_INTERVAL_MILLIS : effective.getIntervalMillis();
        int eventsPerBatch = effective.getEventsPerBatch() == null
            ? DEFAULT_EVENTS_PER_BATCH : effective.getEventsPerBatch();
        if (intervalMillis <= 0 || eventsPerBatch <= 0) {
            return ResponseEntity.badRequest().body("intervalMillis and eventsPerBatch must be positive");
        }
        String batchId = effective.getBatchId() == null || effective.getBatchId().isBlank()
            ? "realtime-" + System.currentTimeMillis() : effective.getBatchId();

        try {
            return ResponseEntity.ok(producer.start(RealtimeSimulationConfig.builder()
                .batchId(batchId)
                .intervalMillis(intervalMillis)
                .eventsPerBatch(eventsPerBatch)
                .build()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }

    @PostMapping("/stop")
    public ResponseEntity<?> stop() {
        if (!producer.stop()) {
            return ResponseEntity.ok("Realtime simulator is not running");
        }
        return ResponseEntity.ok(producer.status());
    }

    @GetMapping("/status")
    public RealtimeStatus status() {
        return producer.status();
    }

    @Getter
    @Setter
    public static class StartRequest {
        private String batchId;
        private Integer intervalMillis;
        private Integer eventsPerBatch;
    }
}
