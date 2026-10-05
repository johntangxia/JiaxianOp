package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.domain.ActiveActionType;
import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.domain.Channel;
import com.jiaxian.analytics.repository.ActiveActionLogRepository;
import com.jiaxian.analytics.repository.ChannelRepository;
import com.jiaxian.analytics.repository.CommentRepository;
import com.jiaxian.analytics.repository.ContentRepository;
import com.jiaxian.analytics.repository.EmployeeRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class RealtimeEventProducer {

    private final KafkaTemplate<String, RealtimeEvent> kafkaTemplate;
    private final EmployeeRepository employeeRepository;
    private final ContentRepository contentRepository;
    private final CommentRepository commentRepository;
    private final ChannelRepository channelRepository;
    private final RealtimeEventPlanner planner;
    private final String topic;
    /** 为空时（单元测试用的构造函数）退回旧的均匀抽样。 */
    private final ActiveActionLogRepository activeActionLogRepository;
    private volatile RealtimeAudience audience;

    private final AtomicLong sentCount = new AtomicLong();
    private ScheduledExecutorService executor;
    private boolean running;
    private String batchId;
    private LocalDateTime startedAt;

    @Autowired
    public RealtimeEventProducer(KafkaTemplate<String, RealtimeEvent> kafkaTemplate,
                                 EmployeeRepository employeeRepository,
                                 ContentRepository contentRepository,
                                 CommentRepository commentRepository,
                                 ChannelRepository channelRepository,
                                 ActiveActionLogRepository activeActionLogRepository,
                                 @Value("${realtime.topic:" + RealtimeConfig.COMMUNITY_EVENTS_TOPIC + "}") String topic) {
        this(kafkaTemplate, employeeRepository, contentRepository, commentRepository, channelRepository,
            new RealtimeEventPlanner(new Random()), topic, activeActionLogRepository);
    }

    RealtimeEventProducer(KafkaTemplate<String, RealtimeEvent> kafkaTemplate,
                          EmployeeRepository employeeRepository,
                          ContentRepository contentRepository,
                          CommentRepository commentRepository,
                          ChannelRepository channelRepository,
                          RealtimeEventPlanner planner) {
        this(kafkaTemplate, employeeRepository, contentRepository, commentRepository, channelRepository, planner,
            RealtimeConfig.COMMUNITY_EVENTS_TOPIC, null);
    }

    RealtimeEventProducer(KafkaTemplate<String, RealtimeEvent> kafkaTemplate,
                          EmployeeRepository employeeRepository,
                          ContentRepository contentRepository,
                          CommentRepository commentRepository,
                          ChannelRepository channelRepository,
                          RealtimeEventPlanner planner,
                          String topic) {
        this(kafkaTemplate, employeeRepository, contentRepository, commentRepository, channelRepository, planner, topic,
            null);
    }

    RealtimeEventProducer(KafkaTemplate<String, RealtimeEvent> kafkaTemplate,
                          EmployeeRepository employeeRepository,
                          ContentRepository contentRepository,
                          CommentRepository commentRepository,
                          ChannelRepository channelRepository,
                          RealtimeEventPlanner planner,
                          String topic,
                          ActiveActionLogRepository activeActionLogRepository) {
        this.activeActionLogRepository = activeActionLogRepository;
        this.topic = topic;
        this.kafkaTemplate = kafkaTemplate;
        this.employeeRepository = employeeRepository;
        this.contentRepository = contentRepository;
        this.commentRepository = commentRepository;
        this.channelRepository = channelRepository;
        this.planner = planner;
    }

    public synchronized RealtimeStatus start(RealtimeSimulationConfig config) {
        if (running) {
            throw new IllegalStateException("Realtime simulator is already running (batchId=" + batchId + ")");
        }
        List<Employee> employees = employeeRepository.findAll();
        List<Content> contents = contentRepository.findAll();
        if (employees.isEmpty() || contents.isEmpty()) {
            throw new IllegalStateException("No employees or content found; run the historical backfill first");
        }

        Map<String, Long> compositeChannelIds = new HashMap<>();
        for (Channel channel : channelRepository.findByCategory(Channel.CATEGORY_COMPOSITE)) {
            compositeChannelIds.put(channel.getName(), channel.getId());
        }

        audience = buildAudience(channelRepository.findAll());

        sentCount.set(0);
        batchId = config.getBatchId();
        startedAt = LocalDateTime.now();
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(() -> runBatchSafely(config, employees, contents, compositeChannelIds),
            0, config.getIntervalMillis(), TimeUnit.MILLISECONDS);
        running = true;
        return status();
    }

    /**
     * 受众：演员权重 = 最近 90 天（以最近一次登录为终点）的登录次数，让常驻的人多、淡出的人几乎不出现；
     * 没有登录历史、或者没有登录记录仓库时返回 null（退回旧的均匀抽样）。
     */
    private RealtimeAudience buildAudience(List<Channel> channels) {
        if (activeActionLogRepository == null) {
            return null;
        }
        LocalDateTime latest = activeActionLogRepository.latestOccurTime(ActiveActionType.LOGIN).orElse(null);
        if (latest == null) {
            return null;
        }
        Map<Long, Double> weights = new HashMap<>();
        for (Object[] row : activeActionLogRepository.countByEmployeeSince(ActiveActionType.LOGIN, latest.minusDays(90))) {
            weights.put(((Number) row[0]).longValue(), ((Number) row[1]).doubleValue());
        }
        Map<Long, String> channelNames = new HashMap<>();
        channels.forEach(c -> channelNames.put(c.getId(), c.getName()));
        return new RealtimeAudience(weights, channelNames);
    }

    /** @return true 表示这次调用确实把一个运行中的模拟停掉了；false 表示本来就没在运行 */
    public synchronized boolean stop() {
        if (!running) {
            return false;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        kafkaTemplate.flush();
        running = false;
        return true;
    }

    public synchronized RealtimeStatus status() {
        return new RealtimeStatus(running, batchId, sentCount.get(), startedAt);
    }

    @PreDestroy
    void shutdown() {
        stop();
    }

    void publishBatch(RealtimeSimulationConfig config, List<Employee> employees, List<Content> contents,
                      Map<String, Long> compositeChannelIds) {
        RealtimeAudience currentAudience = audience;
        List<RealtimeEvent> events = currentAudience == null
            ? planner.planBatch(config.getEventsPerBatch(), config.getBatchId(), LocalDateTime.now(), employees,
                contents, commentRepository::findByContentId, compositeChannelIds)
            : planner.planCalibratedBatch(config.getEventsPerBatch(), config.getBatchId(), LocalDateTime.now(), employees,
                contents, commentRepository::findByContentId, compositeChannelIds, currentAudience);
        for (RealtimeEvent event : events) {
            kafkaTemplate.send(topic, event.partitionKey(), event)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        log.error("Failed to publish realtime event {}", event, error);
                    }
                });
            sentCount.incrementAndGet();
        }
    }

    // scheduleWithFixedDelay 里的任务一旦抛异常，后续调度会被静默取消，所以这里必须兜住所有异常。
    private void runBatchSafely(RealtimeSimulationConfig config, List<Employee> employees, List<Content> contents,
                                Map<String, Long> compositeChannelIds) {
        try {
            publishBatch(config, employees, contents, compositeChannelIds);
        } catch (Exception e) {
            log.error("Realtime batch failed, will retry on the next tick", e);
        }
    }
}
