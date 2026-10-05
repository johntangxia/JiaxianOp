package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.domain.PointsCategory;
import com.jiaxian.analytics.domain.PointsLedger;
import com.jiaxian.analytics.repository.PointsLedgerRepository;
import com.jiaxian.analytics.repository.PvLogRepository;
import com.jiaxian.analytics.simulator.HistoricalBackfillService;
import com.jiaxian.analytics.simulator.SimulationConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 端到端：真实 Kafka（producer → topic → consumer）+ 真实 OpenGauss（积分引擎落库）+ 真实 Redis（今日计数器）。
 * 前提：docker compose 里 opengauss / redis / kafka 都在运行。
 * 只有这个测试打开 Kafka 消费者，并在类结束后关闭上下文，避免消费者残留在缓存的上下文里干扰其他测试。
 */
@SpringBootTest(properties = "realtime.consumer.auto-startup=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RealtimePipelineIT {

    @Autowired private HistoricalBackfillService backfillService;
    @Autowired private RealtimeEventProducer producer;
    @Autowired private RealtimeEventConsumer consumer;
    @Autowired private PvLogRepository pvLogRepository;
    @Autowired private PointsLedgerRepository pointsLedgerRepository;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void producedEventsAreConsumedPersistedWithPointsAndCountedInRedis() {
        backfillService.runBackfill(SimulationConfig.builder()
            .batchId("pipeline-seed")
            .employeeCount(20)
            .accountCount(10)
            .teamAccountRatio(0.2)
            .contentCount(30)
            .historyStart(LocalDate.of(2026, 1, 1))
            .historyEnd(LocalDate.of(2026, 3, 1))
            .randomSeed(11)
            .build());

        String batchId = "pipeline-it-" + System.currentTimeMillis();
        String today = LocalDate.now().toString();
        long redisPvBefore = sumCounters("rt:pv:channel:*:" + today);
        long redisEngagementBefore = sumCounters("rt:engagement:channel:*:" + today);

        producer.start(RealtimeSimulationConfig.builder()
            .batchId(batchId).intervalMillis(100).eventsPerBatch(20).build());
        try {
            await().atMost(60, TimeUnit.SECONDS).until(() -> producer.status().getSentCount() >= 60);
        } finally {
            producer.stop();
        }

        // 等消费者把这个批次里已发出的事件全部处理完（成功或被跳过都算处理过）
        long sent = producer.status().getSentCount();
        await().atMost(90, TimeUnit.SECONDS).until(() -> consumer.getProcessedCount(batchId) >= sent);

        List<PointsLedger> ledger = pointsLedgerRepository.findBySimulationBatchId(batchId);
        long pvRows = pvLogRepository.countBySimulationBatchId(batchId);

        assertThat(pvRows).isGreaterThan(0);
        // 实时浏览也带来源和停留；入口来源的点击落了"已点击"曝光，同批次里还有若干未点击曝光
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pv_log WHERE simulation_batch_id = ?"
            + " AND (source_type IS NULL OR dwell_seconds IS NULL)", Long.class, batchId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM impression WHERE simulation_batch_id = ?",
            Long.class, batchId)).isGreaterThan(0);
        assertThat(ledger).extracting(PointsLedger::getCategory)
            .contains(PointsCategory.ACTIVE, PointsCategory.PASSIVE);
        assertThat(sumCounters("rt:pv:channel:*:" + today) - redisPvBefore)
            .isGreaterThan(0)
            .isGreaterThanOrEqualTo(pvRows);
        assertThat(sumCounters("rt:engagement:channel:*:" + today) - redisEngagementBefore).isGreaterThan(0);
    }

    private long sumCounters(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        return redisTemplate.opsForValue().multiGet(keys).stream()
            .filter(Objects::nonNull)
            .mapToLong(Long::parseLong)
            .sum();
    }
}
