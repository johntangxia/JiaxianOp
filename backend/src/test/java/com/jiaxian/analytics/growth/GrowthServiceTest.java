package com.jiaxian.analytics.growth;

import com.jiaxian.analytics.channel.ChannelCockpitService;
import com.jiaxian.analytics.channel.ChannelPeriod;
import com.jiaxian.analytics.channel.ChannelRow;
import com.jiaxian.analytics.channel.Cockpit;
import com.jiaxian.analytics.channel.ContentMetrics;
import com.jiaxian.analytics.channel.Diagnosis;
import com.jiaxian.analytics.channel.DistributionMetrics;
import com.jiaxian.analytics.channel.SupplyMetrics;
import com.jiaxian.analytics.drill.SubjectQueryRepository;
import com.jiaxian.analytics.drill.SubjectQueryRepository.AccountInfo;
import com.jiaxian.analytics.drill.SubjectQueryRepository.BloggerInfo;
import com.jiaxian.analytics.drill.SubjectQueryRepository.ChannelInfo;
import com.jiaxian.analytics.reader.ActiveDay;
import com.jiaxian.analytics.reader.Member;
import com.jiaxian.analytics.reader.ReaderQueryRepository;
import com.jiaxian.analytics.reader.ReaderQueryRepository.DateSpan;
import com.jiaxian.analytics.reader.ReaderQueryRepository.MemberActivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GrowthServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 10, 5);
    private static final LocalDate AS_OF = LocalDate.of(2025, 9, 4);

    private final ReaderQueryRepository readers = mock(ReaderQueryRepository.class);
    private final SubjectQueryRepository subjects = mock(SubjectQueryRepository.class);
    private final ChannelCockpitService cockpit = mock(ChannelCockpitService.class);
    private final GrowthQueryRepository queries = mock(GrowthQueryRepository.class);
    private final GrowthAlertRepository alertRepository = mock(GrowthAlertRepository.class);
    private final GrowthTaskRepository taskRepository = mock(GrowthTaskRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2025-10-05T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private GrowthService service;

    @BeforeEach
    void setUp() {
        service = new GrowthService(readers, subjects, cockpit, queries, alertRepository, taskRepository,
            new MetricEvaluator(cockpit, subjects, readers), clock);
        when(alertRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(taskRepository.save(any())).thenAnswer(inv -> {
            GrowthTask task = inv.getArgument(0);
            if (task.getId() == null) {
                task.setId(42L);
            }
            return task;
        });
        when(readers.loginPostSpan()).thenReturn(Optional.of(new DateSpan(LocalDate.of(2025, 1, 1), AS_OF)));
    }

    // ---- 统计日与参数 ----

    @Test
    void asOfDefaultsToTheLastLoginOrPostDayAndFallsBackToToday() {
        assertThat(service.resolveAsOf(null)).isEqualTo(AS_OF);
        assertThat(service.resolveAsOf(LocalDate.of(2025, 8, 1))).isEqualTo(LocalDate.of(2025, 8, 1));
        when(readers.loginPostSpan()).thenReturn(Optional.empty());
        assertThat(service.resolveAsOf(null)).isEqualTo(TODAY);
    }

    @Test
    void asOfInTheFutureIsRejected() {
        assertThatThrownBy(() -> service.resolveAsOf(TODAY.plusDays(1))).isInstanceOf(InvalidGrowthRequestException.class);
    }

    @Test
    void departmentsAndListsValidateTheirParameters() {
        assertThatThrownBy(() -> service.departments(null, 6)).isInstanceOf(InvalidGrowthRequestException.class);
        assertThatThrownBy(() -> service.departments(null, 91)).isInstanceOf(InvalidGrowthRequestException.class);
        assertThatThrownBy(() -> service.hotContents(null, 0)).isInstanceOf(InvalidGrowthRequestException.class);
        assertThatThrownBy(() -> service.hotContents(null, 101)).isInstanceOf(InvalidGrowthRequestException.class);
        assertThatThrownBy(() -> service.alerts(null, null, null, 0, 0)).isInstanceOf(InvalidGrowthRequestException.class);
        assertThatThrownBy(() -> service.alerts(null, null, null, 20, -1)).isInstanceOf(InvalidGrowthRequestException.class);
        assertThatThrownBy(() -> service.tasks(null, 101, 0, null)).isInstanceOf(InvalidGrowthRequestException.class);
    }

    @Test
    void departmentsUseTheRequestedWindowAndThePreviousOne() {
        when(readers.members()).thenReturn(List.of(new Member(1, "甲", "半导体", null)));
        when(readers.memberActivity(AS_OF.minusDays(6), AS_OF)).thenReturn(List.of(new MemberActivity(1, 0, 0, 2, 2, 1, AS_OF)));

        DepartmentReport report = service.departments(null, 7);

        assertThat(report.from()).isEqualTo(AS_OF.minusDays(6));
        assertThat(report.previousTo()).isEqualTo(AS_OF.minusDays(7));
        assertThat(report.previousFrom()).isEqualTo(AS_OF.minusDays(13));
        assertThat(report.departments().get(0).current().penetration()).isEqualTo(1.0);
        assertThat(report.departments().get(0).previous().penetration()).isZero();
    }

    // ---- 告警重算 ----

    private void stubAlertInputs() {
        ChannelPeriod empty = new ChannelPeriod(DistributionMetrics.EMPTY, ContentMetrics.EMPTY, SupplyMetrics.EMPTY, null);
        ChannelRow row = new ChannelRow(10, "半导体", "领域频道", empty, empty,
            List.of(new Diagnosis("LOW_INTERACTION", Diagnosis.WARN, "互动率偏低"), new Diagnosis("HIGH_CONCENTRATION", Diagnosis.INFO, "集中")));
        when(cockpit.cockpit(AS_OF.minusDays(29), AS_OF)).thenReturn(new Cockpit(AS_OF.minusDays(29), AS_OF,
            AS_OF.minusDays(59), AS_OF.minusDays(30), List.of(), List.of(row), java.util.Map.of(), java.util.Map.of()));
        List<Member> members = new ArrayList<>();
        List<ActiveDay> days = new ArrayList<>();
        for (int id = 1; id <= 10; id++) {
            members.add(new Member(id, "员工" + id, "半导体", null));
            for (int d = 0; d < 7; d++) {
                days.add(new ActiveDay(id, AS_OF.minusDays(13).plusDays(d)));       // 前 7 天每天 10 人
            }
        }
        for (int d = 7; d < 14; d++) {
            days.add(new ActiveDay(1, AS_OF.minusDays(13).plusDays(d)));            // 近 7 天每天 1 人
        }
        when(readers.members()).thenReturn(members);
        when(readers.activeDays(AS_OF.minusDays(13), AS_OF)).thenReturn(days);
        when(queries.churnRisks(AS_OF, 30)).thenReturn(List.of(new ChurnRisk(5, "博主五", 2), new ChurnRisk(6, "博主六", 9)));
    }

    @Test
    void rebuildCreatesAnOpenAlertForEveryRuleThatFires() {
        stubAlertInputs();
        when(alertRepository.findByKindAndEntityTypeAndEntityNameAndPeriodEnd(any(), any(), any(), any())).thenReturn(Optional.empty());

        AlertRebuildResult result = service.rebuildAlerts(null);

        ArgumentCaptor<GrowthAlert> saved = ArgumentCaptor.forClass(GrowthAlert.class);
        verify(alertRepository, org.mockito.Mockito.times(4)).save(saved.capture());
        assertThat(result.asOf()).isEqualTo(AS_OF);
        assertThat(result.created()).isEqualTo(4);
        assertThat(result.updated()).isZero();
        assertThat(result.total()).isEqualTo(4);
        assertThat(saved.getAllValues()).extracting(GrowthAlert::getKind)
            .containsExactlyInAnyOrder("CHANNEL_LOW_INTERACTION", "OVERALL_DAU_DROP", "BLOGGER_CHURN_RISK", "BLOGGER_CHURN_RISK");
        assertThat(saved.getAllValues()).allSatisfy(a -> {
            assertThat(a.getStatus()).isEqualTo(AlertStatus.OPEN);
            assertThat(a.getPeriodEnd()).isEqualTo(AS_OF);
        });
        GrowthAlert dau = saved.getAllValues().stream().filter(a -> a.getKind().equals("OVERALL_DAU_DROP")).findFirst().orElseThrow();
        assertThat(dau.getSeverity()).isEqualTo("CRITICAL");     // 10 → 1，下降 90%
        assertThat(dau.getEntityType()).isEqualTo(GrowthEntityType.OVERALL);
    }

    @Test
    void rebuildIsIdempotentKeepsStatusAndNeverReopensAClosedAlert() {
        stubAlertInputs();
        GrowthAlert acked = GrowthAlert.builder().id(1L).kind("CHANNEL_LOW_INTERACTION").severity("WARN").entityType(GrowthEntityType.CHANNEL)
            .entityName("半导体").periodEnd(AS_OF).message("旧说明").status(AlertStatus.ACKED).build();
        GrowthAlert closed = GrowthAlert.builder().id(2L).kind("OVERALL_DAU_DROP").severity("WARN").entityType(GrowthEntityType.OVERALL)
            .entityName("全社区").periodEnd(AS_OF).message("旧").status(AlertStatus.CLOSED).build();
        when(alertRepository.findByKindAndEntityTypeAndEntityNameAndPeriodEnd(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(alertRepository.findByKindAndEntityTypeAndEntityNameAndPeriodEnd("CHANNEL_LOW_INTERACTION", GrowthEntityType.CHANNEL, "半导体", AS_OF))
            .thenReturn(Optional.of(acked));
        when(alertRepository.findByKindAndEntityTypeAndEntityNameAndPeriodEnd("OVERALL_DAU_DROP", GrowthEntityType.OVERALL, "全社区", AS_OF))
            .thenReturn(Optional.of(closed));

        AlertRebuildResult result = service.rebuildAlerts(null);

        assertThat(result.created()).isEqualTo(2);       // 两条博主流失预警是新的
        assertThat(result.updated()).isEqualTo(1);       // 已确认的那条更新说明
        assertThat(acked.getStatus()).isEqualTo(AlertStatus.ACKED);
        assertThat(acked.getMessage()).isEqualTo("互动率偏低");
        assertThat(closed.getStatus()).isEqualTo(AlertStatus.CLOSED);
        assertThat(closed.getMessage()).isEqualTo("旧");     // 已关闭的不动
        verify(alertRepository, never()).save(closed);
    }

    @Test
    void updateAlertStatusChangesTheStatusAndRejectsMissingOnes() {
        GrowthAlert alert = GrowthAlert.builder().id(1L).status(AlertStatus.OPEN).build();
        when(alertRepository.findById(1L)).thenReturn(Optional.of(alert));

        GrowthAlert updated = service.updateAlertStatus(1L, AlertStatus.ACKED);

        assertThat(updated.getStatus()).isEqualTo(AlertStatus.ACKED);
        assertThat(updated.getUpdatedAt()).isNotNull();
        assertThatThrownBy(() -> service.updateAlertStatus(2L, AlertStatus.CLOSED)).isInstanceOf(GrowthNotFoundException.class);
        assertThatThrownBy(() -> service.updateAlertStatus(1L, null)).isInstanceOf(InvalidGrowthRequestException.class);
    }

    // ---- 创建任务 ----

    private CreateTaskRequest request(GrowthEntityType type, Long entityId, String entityName, String metric, Double target) {
        return new CreateTaskRequest("提升渗透", TaskType.ONBOARD_NEWCOMERS, type, entityId, entityName, metric, target, "张三",
            null, null, "备注", null);
    }

    private void stubOverallMembers() {
        when(readers.members()).thenReturn(List.of(new Member(1, "甲", "光", null), new Member(2, "乙", "光", null)));
    }

    @Test
    void createTaskStoresABaselineFromTheThirtyDaysBeforeTheStartDate() {
        stubOverallMembers();
        // 开始日默认 = asOf（09-04），基线窗口 [08-05, 09-03]：2 个成员里 1 个活跃
        when(readers.memberActivity(AS_OF.minusDays(30), AS_OF.minusDays(1))).thenReturn(List.of(new MemberActivity(1, 0, 0, 1, 1, 1, AS_OF)));
        // 当前窗口 [08-06, 09-04]：2 个都活跃
        when(readers.memberActivity(AS_OF.minusDays(29), AS_OF)).thenReturn(List.of(
            new MemberActivity(1, 0, 0, 1, 1, 1, AS_OF), new MemberActivity(2, 0, 0, 1, 1, 1, AS_OF)));

        TaskView view = service.createTask(request(GrowthEntityType.OVERALL, null, null, "ACTIVE_RATE", 1.0));

        GrowthTask task = view.task();
        assertThat(task.getBaselineValue()).isEqualTo(0.5);
        assertThat(task.getStartDate()).isEqualTo(AS_OF);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(task.getEntityName()).isEqualTo("全社区");
        assertThat(task.getOwnerName()).isEqualTo("张三");
        assertThat(view.currentValue()).isEqualTo(1.0);
        assertThat(view.progress()).isCloseTo(1.0, within(1e-9));   // 基线 0.5 → 目标 1.0，当前 1.0
    }

    @Test
    void createTaskResolvesTheCanonicalEntityName() {
        when(subjects.channel(10)).thenReturn(Optional.of(new ChannelInfo(10, "半导体", "领域频道")));
        when(subjects.account(7)).thenReturn(Optional.of(new AccountInfo(7, "账号七", "TEAM")));
        when(subjects.blogger(8)).thenReturn(Optional.of(new BloggerInfo(8, "博主八", "光", null, false)));
        when(readers.depts()).thenReturn(List.of("半导体", "光"));
        when(readers.members()).thenReturn(List.of());

        assertThat(service.createTask(request(GrowthEntityType.CHANNEL, 10L, "乱写的名字", "PV", 100.0)).task().getEntityName()).isEqualTo("半导体");
        assertThat(service.createTask(request(GrowthEntityType.ACCOUNT, 7L, null, "POSTS", 5.0)).task().getEntityName()).isEqualTo("账号七");
        assertThat(service.createTask(request(GrowthEntityType.BLOGGER, 8L, null, "PV", 5.0)).task().getEntityName()).isEqualTo("博主八");
        assertThat(service.createTask(request(GrowthEntityType.DEPARTMENT, null, " 光 ", "PENETRATION", 0.5)).task().getEntityName()).isEqualTo("光");
    }

    @Test
    void createTaskRejectsInvalidInput() {
        stubOverallMembers();
        when(readers.depts()).thenReturn(List.of("光"));
        when(subjects.channel(1)).thenReturn(Optional.of(new ChannelInfo(1, "推荐", "综合频道")));

        assertInvalid(new CreateTaskRequest(" ", null, GrowthEntityType.OVERALL, null, null, "ACTIVE_RATE", 1.0, null, null, null, null, null));
        assertInvalid(new CreateTaskRequest("x".repeat(201), null, GrowthEntityType.OVERALL, null, null, "ACTIVE_RATE", 1.0, null, null, null, null, null));
        assertInvalid(request(null, null, null, "ACTIVE_RATE", 1.0));
        assertInvalid(request(GrowthEntityType.OVERALL, null, null, null, 1.0));
        assertInvalid(request(GrowthEntityType.OVERALL, null, null, "NOT_A_METRIC", 1.0));
        assertInvalid(request(GrowthEntityType.OVERALL, null, null, "PV", 1.0));                        // 指标和对象类型不匹配
        assertInvalid(request(GrowthEntityType.OVERALL, null, null, "ACTIVE_RATE", null));
        assertInvalid(request(GrowthEntityType.OVERALL, null, null, "ACTIVE_RATE", Double.NaN));
        assertInvalid(request(GrowthEntityType.CHANNEL, null, null, "PV", 1.0));                        // 频道没给 id
        assertInvalid(request(GrowthEntityType.CHANNEL, 1L, null, "PV", 1.0));                          // 综合频道
        assertInvalid(request(GrowthEntityType.CHANNEL, 999L, null, "PV", 1.0));                        // 不存在
        assertInvalid(request(GrowthEntityType.ACCOUNT, 999L, null, "PV", 1.0));
        assertInvalid(request(GrowthEntityType.BLOGGER, 999L, null, "PV", 1.0));
        assertInvalid(request(GrowthEntityType.DEPARTMENT, null, "没有这个部门", "PENETRATION", 0.5));
        assertInvalid(new CreateTaskRequest("t", null, GrowthEntityType.OVERALL, null, null, "ACTIVE_RATE", 1.0, "n".repeat(51), null, null, null, null));
        assertInvalid(new CreateTaskRequest("t", null, GrowthEntityType.OVERALL, null, null, "ACTIVE_RATE", 1.0, null, null, null, "n".repeat(501), null));
        assertInvalid(new CreateTaskRequest("t", null, GrowthEntityType.OVERALL, null, null, "ACTIVE_RATE", 1.0, null,
            LocalDate.of(2025, 9, 1), LocalDate.of(2025, 8, 1), null, null));                           // 到期早于开始
        assertInvalid(new CreateTaskRequest("t", null, GrowthEntityType.OVERALL, null, null, "ACTIVE_RATE", 1.0, null, null, null, null, 77L));  // 告警不存在
        verify(taskRepository, never()).save(any());
    }

    private void assertInvalid(CreateTaskRequest request) {
        assertThatThrownBy(() -> service.createTask(request)).as(request.toString()).isInstanceOf(InvalidGrowthRequestException.class);
    }

    @Test
    void createTaskAcceptsAnExistingAlertAndExplicitDates() {
        stubOverallMembers();
        when(alertRepository.existsById(5L)).thenReturn(true);

        GrowthTask task = service.createTask(new CreateTaskRequest("从告警来的任务", TaskType.OTHER, GrowthEntityType.OVERALL, null, null,
            "ACTIVE_USERS", 10.0, "  ", LocalDate.of(2025, 8, 1), LocalDate.of(2025, 9, 1), "  ", 5L)).task();

        assertThat(task.getAlertId()).isEqualTo(5L);
        assertThat(task.getStartDate()).isEqualTo(LocalDate.of(2025, 8, 1));
        assertThat(task.getDueDate()).isEqualTo(LocalDate.of(2025, 9, 1));
        assertThat(task.getOwnerName()).isNull();      // 空白当作没填
        assertThat(task.getNote()).isNull();
    }

    // ---- 效果追踪、修改、删除 ----

    private GrowthTask existingTask() {
        return GrowthTask.builder().id(9L).title("任务").taskType(TaskType.OTHER).entityType(GrowthEntityType.OVERALL)
            .metric("ACTIVE_USERS").baselineValue(1.0).targetValue(3.0).startDate(AS_OF.minusDays(10)).status(TaskStatus.DOING).build();
    }

    @Test
    void taskDetailHasEightWeeklyWindowsAndMarksTheOneContainingTheStartDate() {
        when(taskRepository.findById(9L)).thenReturn(Optional.of(existingTask()));
        stubOverallMembers();
        when(readers.memberActivity(any(), any())).thenReturn(List.of(new MemberActivity(1, 0, 0, 1, 1, 1, AS_OF)));

        TaskDetail detail = service.task(9L, null);

        assertThat(detail.asOf()).isEqualTo(AS_OF);
        assertThat(detail.series()).hasSize(8);
        assertThat(detail.series().get(7).windowEnd()).isEqualTo(AS_OF);
        assertThat(detail.series().get(0).windowEnd()).isEqualTo(AS_OF.minusDays(49));
        assertThat(detail.series()).extracting(EffectPoint::value).containsOnly(1.0);
        // 开始日是 asOf 之前 10 天：落在 [asOf−13, asOf−7] 这个窗口，也就是倒数第 2 个
        assertThat(detail.series()).extracting(EffectPoint::containsStart)
            .containsExactly(false, false, false, false, false, false, true, false);
        assertThat(detail.view().currentValue()).isEqualTo(1.0);
        assertThat(detail.view().progress()).isZero();       // 基线 1，目标 3，当前 1
    }

    @Test
    void anUnknownTaskIsNotFound() {
        assertThatThrownBy(() -> service.task(1L, null)).isInstanceOf(GrowthNotFoundException.class);
        assertThatThrownBy(() -> service.updateTask(1L, new UpdateTaskRequest(null, null, TaskStatus.DONE, null, null, null)))
            .isInstanceOf(GrowthNotFoundException.class);
        assertThatThrownBy(() -> service.deleteTask(1L)).isInstanceOf(GrowthNotFoundException.class);
    }

    @Test
    void updateTaskChangesOnlyTheGivenFieldsAndNeverTheObjectOrBaseline() {
        GrowthTask task = existingTask();
        when(taskRepository.findById(9L)).thenReturn(Optional.of(task));
        stubOverallMembers();

        service.updateTask(9L, new UpdateTaskRequest("新标题", "李四", TaskStatus.DONE, 5.0, AS_OF.plusDays(5), "新备注"));

        assertThat(task.getTitle()).isEqualTo("新标题");
        assertThat(task.getOwnerName()).isEqualTo("李四");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task.getTargetValue()).isEqualTo(5.0);
        assertThat(task.getDueDate()).isEqualTo(AS_OF.plusDays(5));
        assertThat(task.getNote()).isEqualTo("新备注");
        assertThat(task.getBaselineValue()).isEqualTo(1.0);
        assertThat(task.getMetric()).isEqualTo("ACTIVE_USERS");

        service.updateTask(9L, new UpdateTaskRequest(null, null, TaskStatus.CANCELLED, null, null, null));
        assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(task.getTitle()).isEqualTo("新标题");
    }

    @Test
    void updateTaskRejectsBadValues() {
        when(taskRepository.findById(9L)).thenReturn(Optional.of(existingTask()));

        assertThatThrownBy(() -> service.updateTask(9L, new UpdateTaskRequest(" ", null, null, null, null, null)))
            .isInstanceOf(InvalidGrowthRequestException.class);
        assertThatThrownBy(() -> service.updateTask(9L, new UpdateTaskRequest(null, null, null, Double.POSITIVE_INFINITY, null, null)))
            .isInstanceOf(InvalidGrowthRequestException.class);
        assertThatThrownBy(() -> service.updateTask(9L, new UpdateTaskRequest(null, null, null, null, AS_OF.minusDays(30), null)))
            .isInstanceOf(InvalidGrowthRequestException.class);
        assertThatThrownBy(() -> service.updateTask(9L, new UpdateTaskRequest(null, "x".repeat(51), null, null, null, null)))
            .isInstanceOf(InvalidGrowthRequestException.class);
    }

    @Test
    void deleteTaskRemovesAnExistingTask() {
        when(taskRepository.existsById(9L)).thenReturn(true);

        service.deleteTask(9L);

        verify(taskRepository).deleteById(9L);
    }
}
