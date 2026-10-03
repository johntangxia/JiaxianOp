package com.jiaxian.analytics.realtime;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class RealtimeStatus {
    private final boolean running;
    private final String batchId;
    private final long sentCount;
    private final LocalDateTime startedAt;
}
