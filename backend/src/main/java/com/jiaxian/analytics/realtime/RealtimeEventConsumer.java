package com.jiaxian.analytics.realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class RealtimeEventConsumer {

    private final RealtimeEventHandler eventHandler;
    private final RealtimeCounterUpdater counterUpdater;
    private final ConcurrentMap<String, AtomicLong> processedByBatch = new ConcurrentHashMap<>();

    public RealtimeEventConsumer(RealtimeEventHandler eventHandler, RealtimeCounterUpdater counterUpdater) {
        this.eventHandler = eventHandler;
        this.counterUpdater = counterUpdater;
    }

    @KafkaListener(topics = RealtimeConfig.COMMUNITY_EVENTS_TOPIC,
        autoStartup = "${realtime.consumer.auto-startup:true}")
    public void onEvent(RealtimeEvent event) {
        try {
            eventHandler.handle(event);
            counterUpdater.record(event);
        } catch (Exception e) {
            log.error("Failed to process realtime event {}, skipping it", event, e);
        } finally {
            processedByBatch
                .computeIfAbsent(String.valueOf(event.getBatchId()), batch -> new AtomicLong())
                .incrementAndGet();
        }
    }

    /** 某个批次里"已经处理过（不论成功还是被跳过）"的事件数。 */
    public long getProcessedCount(String batchId) {
        AtomicLong count = processedByBatch.get(String.valueOf(batchId));
        return count == null ? 0 : count.get();
    }
}
