package com.jiaxian.analytics.growth;

import com.jiaxian.analytics.channel.ChannelCockpitService;
import com.jiaxian.analytics.channel.ChannelRow;
import com.jiaxian.analytics.channel.Cockpit;
import com.jiaxian.analytics.channel.ChannelQueryRepository.ContentSide;
import com.jiaxian.analytics.drill.ExposureMetrics;
import com.jiaxian.analytics.drill.SubjectFilter;
import com.jiaxian.analytics.drill.SubjectQueryRepository;
import com.jiaxian.analytics.drill.SubjectQueryRepository.ContentInfo;
import com.jiaxian.analytics.drill.SubjectType;
import com.jiaxian.analytics.growth.GrowthQueryRepository.AlertPage;
import com.jiaxian.analytics.growth.GrowthQueryRepository.TaskPage;
import com.jiaxian.analytics.pv.Granularity;
import com.jiaxian.analytics.reader.ActiveDay;
import com.jiaxian.analytics.reader.ActivityAggregator;
import com.jiaxian.analytics.reader.ActivityPoint;
import com.jiaxian.analytics.reader.FunnelFacts;
import com.jiaxian.analytics.reader.Member;
import com.jiaxian.analytics.reader.ReaderQueryRepository;
import com.jiaxian.analytics.reader.ReaderQueryRepository.MemberActivity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 增长闭环：部门渗透、热点内容、告警（重算、查询、改状态）、运营任务（创建、查询、效果追踪、修改、删除）。 */
@Service
public class GrowthService {

    static final int MIN_DAYS = 7;
    static final int MAX_DAYS = 90;
    static final int DEFAULT_DAYS = 30;
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    static final int HOT_DAYS = 3;
    static final int POTENTIAL_DAYS = 30;
    static final int ALERT_CHANNEL_DAYS = 30;
    static final int ACTIVATION_JOIN_LAG_DAYS = 14;
    static final int ACTIVATION_JOIN_SPAN_DAYS = 30;
    static final int TASK_WINDOW_DAYS = 30;
    static final int SERIES_POINTS = 8;
    static final int SERIES_WINDOW_DAYS = 7;
    static final int MAX_TITLE = 200;
    static final int MAX_OWNER = 50;
    static final int MAX_NOTE = 500;

    private final ReaderQueryRepository readers;
    private final SubjectQueryRepository subjects;
    private final ChannelCockpitService cockpitService;
    private final GrowthQueryRepository queries;
    private final GrowthAlertRepository alertRepository;
    private final GrowthTaskRepository taskRepository;
    private final MetricEvaluator evaluator;
    private final Clock clock;

    public GrowthService(ReaderQueryRepository readers, SubjectQueryRepository subjects, ChannelCockpitService cockpitService,
                         GrowthQueryRepository queries, GrowthAlertRepository alertRepository,
                         GrowthTaskRepository taskRepository, MetricEvaluator evaluator, Clock clock) {
        this.readers = readers;
        this.subjects = subjects;
        this.cockpitService = cockpitService;
        this.queries = queries;
        this.alertRepository = alertRepository;
        this.taskRepository = taskRepository;
        this.evaluator = evaluator;
        this.clock = clock;
    }

    /** 统计日：显式传了就用，否则取最近一次登录/发帖的日期，没有数据取今天；不能晚于今天。 */
    LocalDate resolveAsOf(LocalDate asOf) {
        LocalDate today = LocalDate.now(clock);
        LocalDate resolved = asOf != null ? asOf : readers.loginPostSpan().map(s -> s.to()).orElse(today);
        if (resolved.isAfter(today)) {
            throw new InvalidGrowthRequestException("asOf must not be in the future");
        }
        return resolved;
    }

    private static int limitOf(Integer limit) {
        int value = limit != null ? limit : DEFAULT_LIMIT;
        if (value < 1 || value > MAX_LIMIT) {
            throw new InvalidGrowthRequestException("limit must be between 1 and " + MAX_LIMIT);
        }
        return value;
    }

    private static int offsetOf(Integer offset) {
        int value = offset != null ? offset : 0;
        if (value < 0) {
            throw new InvalidGrowthRequestException("offset must not be negative");
        }
        return value;
    }

    // ---- 部门渗透 ----

    public DepartmentReport departments(LocalDate asOf, Integer days) {
        LocalDate to = resolveAsOf(asOf);
        int window = days != null ? days : DEFAULT_DAYS;
        if (window < MIN_DAYS || window > MAX_DAYS) {
            throw new InvalidGrowthRequestException("days must be between " + MIN_DAYS + " and " + MAX_DAYS);
        }
        return departmentReport(to, window);
    }

    private DepartmentReport departmentReport(LocalDate to, int days) {
        LocalDate from = to.minusDays(days - 1L);
        LocalDate previousTo = from.minusDays(1);
        LocalDate previousFrom = previousTo.minusDays(days - 1L);
        return DepartmentPenetrationBuilder.build(readers.members(), activity(from, to), activity(previousFrom, previousTo),
            from, to, previousFrom, previousTo, days);
    }

    private Map<Long, MemberActivity> activity(LocalDate from, LocalDate to) {
        Map<Long, MemberActivity> map = new HashMap<>();
        readers.memberActivity(from, to).forEach(a -> map.put(a.employeeId(), a));
        return map;
    }

    // ---- 热点与潜力内容 ----

    public HotContentReport hotContents(LocalDate asOf, Integer limit) {
        LocalDate to = resolveAsOf(asOf);
        int top = limitOf(limit);
        Map<Long, Long> channels = queries.contentChannels();

        LocalDate hotFrom = to.minusDays(HOT_DAYS - 1L);
        List<ContentStat> recent = stats(hotFrom, to, hotFrom.minusDays(HOT_DAYS), hotFrom.minusDays(1), channels);
        LocalDate potentialFrom = to.minusDays(POTENTIAL_DAYS - 1L);
        List<ContentStat> month = stats(potentialFrom, to, potentialFrom.minusDays(POTENTIAL_DAYS), potentialFrom.minusDays(1), channels);

        List<HotContentRow> hot = new ArrayList<>();
        for (HotContent item : HotContentDetector.detectHot(recent, top)) {
            row(item.contentId(), item.pv(), item.previousPv(), item.hotMultiple(), item.growth(), null,
                item.interactionRate(), item.completionRate()).ifPresent(hot::add);
        }
        List<HotContentRow> potential = new ArrayList<>();
        for (PotentialContent item : HotContentDetector.detectPotential(month, top)) {
            row(item.contentId(), item.pv(), null, null, null, item.qualityMultiple(), item.interactionRate(),
                item.completionRate()).ifPresent(potential::add);
        }
        return new HotContentReport(to, hot, potential);
    }

    private List<ContentStat> stats(LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo,
                                    Map<Long, Long> channels) {
        Map<Long, ContentSide> now = subjects.contentSide(SubjectType.CONTENT, SubjectFilter.NONE, from, to);
        Map<Long, ContentSide> before = subjects.contentSide(SubjectType.CONTENT, SubjectFilter.NONE, previousFrom, previousTo);
        Map<Long, Long> interactions = subjects.interactions(SubjectType.CONTENT, SubjectFilter.NONE, from, to);
        Map<Long, ExposureMetrics> exposure = subjects.exposure(SubjectType.CONTENT, SubjectFilter.NONE, from, to);
        List<ContentStat> stats = new ArrayList<>();
        now.forEach((id, side) -> {
            Long channel = channels.get(id);
            if (channel == null) {
                return;
            }
            ExposureMetrics e = exposure.get(id);
            stats.add(new ContentStat(id, channel, side.pv(), before.containsKey(id) ? before.get(id).pv() : 0,
                interactions.getOrDefault(id, 0L), e == null ? null : e.completionRate()));
        });
        return stats;
    }

    private java.util.Optional<HotContentRow> row(long contentId, long pv, Long previousPv, Double hotMultiple, Double growth,
                                                  Double quality, Double interactionRate, Double completionRate) {
        return subjects.content(contentId).map((ContentInfo c) -> new HotContentRow(c.id(), c.type(), c.channelId(), c.channelName(),
            c.accountId(), c.accountName(), c.publishTime(), pv, previousPv, hotMultiple, growth, quality, interactionRate,
            completionRate));
    }

    // ---- 告警 ----

    @Transactional
    public AlertRebuildResult rebuildAlerts(LocalDate asOf) {
        LocalDate to = resolveAsOf(asOf);
        List<AlertCandidate> candidates = new ArrayList<>();

        Cockpit cockpit = cockpitService.cockpit(to.minusDays(ALERT_CHANNEL_DAYS - 1L), to);
        List<ChannelRow> rows = new ArrayList<>(cockpit.composite());
        rows.addAll(cockpit.domain());
        candidates.addAll(AlertRules.channelDiagnoses(rows));

        List<Member> members = readers.members();
        List<ActiveDay> days = readers.activeDays(to.minusDays(13), to);
        List<ActivityPoint> series = ActivityAggregator.series(members, days, to.minusDays(13), to, Granularity.DAY);
        double previousAverage = series.subList(0, 7).stream().mapToLong(ActivityPoint::activeUsers).average().orElse(0);
        double currentAverage = series.subList(7, 14).stream().mapToLong(ActivityPoint::activeUsers).average().orElse(0);
        AlertRules.dauDrop(currentAverage, previousAverage).ifPresent(candidates::add);

        List<FunnelFacts> joiners = readers.funnelFacts(to.minusDays(ACTIVATION_JOIN_LAG_DAYS + ACTIVATION_JOIN_SPAN_DAYS),
            to.minusDays(ACTIVATION_JOIN_LAG_DAYS));
        AlertRules.lowActivation(joiners.size(), joiners.stream().filter(FunnelFacts::activeInSecondWeek).count())
            .ifPresent(candidates::add);

        candidates.addAll(AlertRules.lowPenetration(departmentReport(to, DEFAULT_DAYS).departments()));
        candidates.addAll(AlertRules.bloggerChurn(queries.churnRisks(to, AlertRules.MAX_CHURN_ALERTS)));

        int created = 0;
        int updated = 0;
        LocalDateTime now = LocalDateTime.now(clock);
        for (AlertCandidate candidate : candidates) {
            var existing = alertRepository.findByKindAndEntityTypeAndEntityNameAndPeriodEnd(candidate.kind(),
                candidate.entityType(), candidate.entityName(), to);
            if (existing.isEmpty()) {
                alertRepository.save(GrowthAlert.builder().kind(candidate.kind()).severity(candidate.severity())
                    .entityType(candidate.entityType()).entityId(candidate.entityId()).entityName(candidate.entityName())
                    .periodEnd(to).message(candidate.message()).status(AlertStatus.OPEN).createdAt(now).updatedAt(now).build());
                created++;
            } else if (existing.get().getStatus() != AlertStatus.CLOSED) {
                GrowthAlert alert = existing.get();
                alert.setSeverity(candidate.severity());
                alert.setEntityId(candidate.entityId());
                alert.setMessage(candidate.message());
                alert.setUpdatedAt(now);
                alertRepository.save(alert);
                updated++;
            }
        }
        return new AlertRebuildResult(to, created, updated, candidates.size());
    }

    public AlertList alerts(AlertStatus status, String kind, GrowthEntityType entityType, Integer limit, Integer offset) {
        int size = limitOf(limit);
        int skip = offsetOf(offset);
        AlertPage page = queries.alerts(status, kind == null || kind.isBlank() ? null : kind.trim(), entityType, size, skip);
        return new AlertList(page.total(), size, skip, queries.alertCounts(), page.alerts());
    }

    @Transactional
    public GrowthAlert updateAlertStatus(long id, AlertStatus status) {
        if (status == null) {
            throw new InvalidGrowthRequestException("status is required");
        }
        GrowthAlert alert = alertRepository.findById(id).orElseThrow(() -> new GrowthNotFoundException("Alert", id));
        alert.setStatus(status);
        alert.setUpdatedAt(LocalDateTime.now(clock));
        return alertRepository.save(alert);
    }

    // ---- 任务 ----

    @Transactional
    public TaskView createTask(CreateTaskRequest request) {
        if (request.title() == null || request.title().isBlank()) {
            throw new InvalidGrowthRequestException("title is required");
        }
        if (request.title().length() > MAX_TITLE) {
            throw new InvalidGrowthRequestException("title must not exceed " + MAX_TITLE + " characters");
        }
        if (request.entityType() == null) {
            throw new InvalidGrowthRequestException("entityType is required");
        }
        TaskMetric metric = parseMetric(request.metric());
        if (!metric.appliesTo(request.entityType())) {
            throw new InvalidGrowthRequestException("Metric " + metric + " does not apply to " + request.entityType());
        }
        if (request.targetValue() == null || request.targetValue().isNaN() || request.targetValue().isInfinite()) {
            throw new InvalidGrowthRequestException("targetValue is required");
        }
        checkLength("owner", request.owner(), MAX_OWNER);
        checkLength("note", request.note(), MAX_NOTE);
        if (request.alertId() != null && !alertRepository.existsById(request.alertId())) {
            throw new InvalidGrowthRequestException("alertId does not exist: " + request.alertId());
        }
        String entityName = resolveEntity(request.entityType(), request.entityId(), request.entityName());
        LocalDate startDate = request.startDate() != null ? request.startDate() : resolveAsOf(null);
        if (request.dueDate() != null && request.dueDate().isBefore(startDate)) {
            throw new InvalidGrowthRequestException("dueDate must not be before startDate");
        }

        Double baseline = evaluator.context().evaluate(request.entityType(), request.entityId(), entityName, metric,
            startDate.minusDays(TASK_WINDOW_DAYS), startDate.minusDays(1));
        LocalDateTime now = LocalDateTime.now(clock);
        GrowthTask task = taskRepository.save(GrowthTask.builder().title(request.title().trim())
            .taskType(request.taskType() != null ? request.taskType() : TaskType.OTHER).entityType(request.entityType())
            .entityId(request.entityId()).entityName(entityName).metric(metric.name()).baselineValue(baseline)
            .targetValue(request.targetValue()).ownerName(blankToNull(request.owner())).startDate(startDate)
            .dueDate(request.dueDate()).status(TaskStatus.TODO).note(blankToNull(request.note())).alertId(request.alertId())
            .createdAt(now).updatedAt(now).build());
        return view(task, evaluator.context(), resolveAsOf(null));
    }

    public TaskList tasks(TaskStatus status, Integer limit, Integer offset, LocalDate asOf) {
        int size = limitOf(limit);
        int skip = offsetOf(offset);
        LocalDate to = resolveAsOf(asOf);
        TaskPage page = queries.tasks(status, size, skip);
        MetricEvaluator.Context context = evaluator.context();
        List<TaskView> views = page.tasks().stream().map(t -> view(t, context, to)).toList();
        return new TaskList(page.total(), size, skip, to, views);
    }

    public TaskDetail task(long id, LocalDate asOf) {
        GrowthTask task = taskRepository.findById(id).orElseThrow(() -> new GrowthNotFoundException("Task", id));
        LocalDate to = resolveAsOf(asOf);
        MetricEvaluator.Context context = evaluator.context();
        TaskMetric metric = TaskMetric.valueOf(task.getMetric());
        List<EffectPoint> series = new ArrayList<>();
        for (int i = SERIES_POINTS - 1; i >= 0; i--) {
            LocalDate end = to.minusDays((long) SERIES_WINDOW_DAYS * i);
            LocalDate start = end.minusDays(SERIES_WINDOW_DAYS - 1L);
            series.add(new EffectPoint(end, context.evaluate(task.getEntityType(), task.getEntityId(), task.getEntityName(), metric,
                start, end), !task.getStartDate().isBefore(start) && !task.getStartDate().isAfter(end)));
        }
        return new TaskDetail(view(task, context, to), to, series);
    }

    @Transactional
    public TaskView updateTask(long id, UpdateTaskRequest request) {
        GrowthTask task = taskRepository.findById(id).orElseThrow(() -> new GrowthNotFoundException("Task", id));
        if (request.title() != null) {
            if (request.title().isBlank() || request.title().length() > MAX_TITLE) {
                throw new InvalidGrowthRequestException("title must be 1-" + MAX_TITLE + " characters");
            }
            task.setTitle(request.title().trim());
        }
        if (request.owner() != null) {
            checkLength("owner", request.owner(), MAX_OWNER);
            task.setOwnerName(blankToNull(request.owner()));
        }
        if (request.note() != null) {
            checkLength("note", request.note(), MAX_NOTE);
            task.setNote(blankToNull(request.note()));
        }
        if (request.status() != null) {
            task.setStatus(request.status());
        }
        if (request.targetValue() != null) {
            if (request.targetValue().isNaN() || request.targetValue().isInfinite()) {
                throw new InvalidGrowthRequestException("targetValue must be a number");
            }
            task.setTargetValue(request.targetValue());
        }
        if (request.dueDate() != null) {
            if (request.dueDate().isBefore(task.getStartDate())) {
                throw new InvalidGrowthRequestException("dueDate must not be before startDate");
            }
            task.setDueDate(request.dueDate());
        }
        task.setUpdatedAt(LocalDateTime.now(clock));
        return view(taskRepository.save(task), evaluator.context(), resolveAsOf(null));
    }

    @Transactional
    public void deleteTask(long id) {
        if (!taskRepository.existsById(id)) {
            throw new GrowthNotFoundException("Task", id);
        }
        taskRepository.deleteById(id);
    }

    // ---- 工具 ----

    private TaskView view(GrowthTask task, MetricEvaluator.Context context, LocalDate asOf) {
        Double current = context.evaluate(task.getEntityType(), task.getEntityId(), task.getEntityName(),
            TaskMetric.valueOf(task.getMetric()), asOf.minusDays(TASK_WINDOW_DAYS - 1L), asOf);
        return new TaskView(task, current, TaskProgress.progress(task.getBaselineValue(), task.getTargetValue(), current));
    }

    private static TaskMetric parseMetric(String metric) {
        if (metric == null || metric.isBlank()) {
            throw new InvalidGrowthRequestException("metric is required");
        }
        try {
            return TaskMetric.valueOf(metric.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidGrowthRequestException("Unknown metric: " + metric);
        }
    }

    /** 校验对象并返回规范的对象名：频道/账号/博主按 id 查名字，部门必须是已有部门，全社区固定名字。 */
    private String resolveEntity(GrowthEntityType type, Long id, String name) {
        switch (type) {
            case CHANNEL -> {
                requireId(type, id);
                return subjects.channel(id).filter(c -> !"综合频道".equals(c.category())).map(c -> c.name())
                    .orElseThrow(() -> new InvalidGrowthRequestException("Domain channel not found: " + id));
            }
            case ACCOUNT -> {
                requireId(type, id);
                return subjects.account(id).map(a -> a.name())
                    .orElseThrow(() -> new InvalidGrowthRequestException("Account not found: " + id));
            }
            case BLOGGER -> {
                requireId(type, id);
                return subjects.blogger(id).map(b -> b.name())
                    .orElseThrow(() -> new InvalidGrowthRequestException("Blogger not found: " + id));
            }
            case DEPARTMENT -> {
                if (name == null || !readers.depts().contains(name.trim())) {
                    throw new InvalidGrowthRequestException("Unknown department: " + name);
                }
                return name.trim();
            }
            default -> {
                return "全社区";
            }
        }
    }

    private static void requireId(GrowthEntityType type, Long id) {
        if (id == null) {
            throw new InvalidGrowthRequestException("entityId is required for " + type);
        }
    }

    private static void checkLength(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw new InvalidGrowthRequestException(field + " must not exceed " + max + " characters");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
