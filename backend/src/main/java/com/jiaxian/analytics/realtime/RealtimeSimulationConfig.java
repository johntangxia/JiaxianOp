package com.jiaxian.analytics.realtime;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RealtimeSimulationConfig {
    private String batchId;
    private int intervalMillis;
    private int eventsPerBatch;
}
