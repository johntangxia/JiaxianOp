package com.jiaxian.analytics.growth;

import com.jiaxian.analytics.channel.ChannelPeriod;
import com.jiaxian.analytics.channel.ChannelRow;
import com.jiaxian.analytics.channel.ContentMetrics;
import com.jiaxian.analytics.channel.Diagnosis;
import com.jiaxian.analytics.channel.DistributionMetrics;
import com.jiaxian.analytics.channel.SupplyMetrics;
import com.jiaxian.analytics.reader.Member;
import com.jiaxian.analytics.reader.ReaderQueryRepository.MemberActivity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GrowthLogicTest {

    private static final LocalDate TO = LocalDate.of(2025, 6, 30);
    private static final LocalDate FROM = TO.minusDays(29);
    private static final LocalDate PREV_TO = FROM.minusDays(1);
    private static final LocalDate PREV_FROM = PREV_TO.minusDays(29);

    private static Member m(long id, String dept, LocalDate join) {
        return new Member(id, "员工" + id, dept, join);
    }

    private static MemberActivity act(long id, long posts, long interactions, long views, long events) {
        return new MemberActivity(id, posts, interactions, views, events, 3, TO);
    }

    // ---- 部门渗透率 ----

    @Test
    void penetrationIsActiveMembersOverMembersWithRolesAndNewMembers() {
        List<Member> members = List.of(m(1, "半导体", null), m(2, "半导体", null), m(3, "半导体", FROM.plusDays(3)),
            m(4, "半导体", TO.plusDays(1)), m(5, "光", null));
        Map<Long, MemberActivity> now = Map.of(
            1L, act(1, 1, 0, 4, 5), 2L, act(2, 0, 0, 9, 9), 3L, act(3, 0, 2, 2, 4), 5L, act(5, 0, 0, 0, 1));

        DepartmentReport report = DepartmentPenetrationBuilder.build(members, now, Map.of(), FROM, TO, PREV_FROM, PREV_TO, 30);

        DepartmentRow semiconductor = report.departments().stream().filter(r -> r.dept().equals("半导体")).findFirst().orElseThrow();
        DeptPeriod current = semiconductor.current();
        assertThat(current.members()).isEqualTo(3);                      // 员工 4 在窗口结束后才加入
        assertThat(current.active()).isEqualTo(3);
        assertThat(current.penetration()).isEqualTo(1.0);
        assertThat(current.creators()).isEqualTo(1);
        assertThat(current.interactors()).isEqualTo(1);
        assertThat(current.lurkers()).isEqualTo(1);
        assertThat(current.newMembers()).isEqualTo(1);                   // 员工 3 在窗口内加入
        DeptPeriod previous = semiconductor.previous();
        assertThat(previous.members()).isEqualTo(2);                     // 上期窗口结束时员工 3 还没加入
        assertThat(previous.active()).isZero();
        assertThat(previous.penetration()).isZero();
    }

    @Test
    void departmentsAreSortedByPenetrationAndOverallCoversEveryone() {
        List<Member> members = List.of(m(1, "甲", null), m(2, "甲", null), m(3, "乙", null), m(4, "丙", null), m(5, null, null));
        Map<Long, MemberActivity> now = Map.of(1L, act(1, 0, 0, 3, 3), 3L, act(3, 0, 0, 3, 3), 4L, act(4, 0, 0, 3, 3));

        DepartmentReport report = DepartmentPenetrationBuilder.build(members, now, Map.of(), FROM, TO, PREV_FROM, PREV_TO, 30);

        assertThat(report.departments()).extracting(DepartmentRow::dept).containsExactly("丙", "乙", "甲", "未分配部门");
        assertThat(report.overall().dept()).isEqualTo("全社区");
        assertThat(report.overall().current().members()).isEqualTo(5);
        assertThat(report.overall().current().active()).isEqualTo(3);
        assertThat(report.overall().current().penetration()).isCloseTo(0.6, within(1e-9));
    }

    @Test
    void anActivityRecordWithoutEventsDoesNotCountAsActive() {
        DepartmentReport report = DepartmentPenetrationBuilder.build(List.of(m(1, "甲", null)), Map.of(1L, act(1, 0, 0, 0, 0)),
            Map.of(), FROM, TO, PREV_FROM, PREV_TO, 30);

        assertThat(report.departments().get(0).current().active()).isZero();
    }

    @Test
    void aDepartmentWithoutMembersYetHasNoPenetration() {
        DepartmentReport report = DepartmentPenetrationBuilder.build(List.of(m(1, "甲", TO.plusDays(5))), Map.of(), Map.of(),
            FROM, TO, PREV_FROM, PREV_TO, 30);

        assertThat(report.departments().get(0).current().penetration()).isNull();
    }

    // ---- 热点与潜力内容 ----

    private static ContentStat stat(long id, long channel, long pv, long previous, long interactions, Double completion) {
        return new ContentStat(id, channel, pv, previous, interactions, completion);
    }

    @Test
    void hotContentNeedsTenViewsAndThreeTimesTheChannelBaseline() {
        // 频道 1 里有浏览的内容 PV：2, 3, 4, 5, 40 → 中位数 4，基线 4；40 ≥ 10 且 ≥ 12 → 热点；5 不够 10
        List<ContentStat> stats = List.of(stat(1, 1, 2, 0, 0, null), stat(2, 1, 3, 0, 0, null), stat(3, 1, 4, 0, 0, null),
            stat(4, 1, 5, 0, 0, null), stat(5, 1, 40, 8, 4, 0.5), stat(6, 1, 0, 0, 0, null));

        List<HotContent> hot = HotContentDetector.detectHot(stats, 10);

        assertThat(hot).extracting(HotContent::contentId).containsExactly(5L);
        assertThat(hot.get(0).hotMultiple()).isEqualTo(10.0);
        assertThat(hot.get(0).growth()).isEqualTo(5.0);
        assertThat(hot.get(0).interactionRate()).isEqualTo(0.1);
        assertThat(hot.get(0).completionRate()).isEqualTo(0.5);
    }

    @Test
    void theBaselineIsPerChannelAndAtLeastOne() {
        // 频道 2 只有一篇内容有浏览（PV 12）：基线 12，12 < 3 × 12 → 不是热点；频道 3 里 PV 10 的内容基线按 1 算 → 热点
        List<ContentStat> stats = List.of(stat(1, 2, 12, 0, 0, null), stat(2, 3, 10, 0, 0, null));

        assertThat(HotContentDetector.detectHot(stats, 10)).extracting(HotContent::contentId).isEmpty();
        assertThat(HotContentDetector.detectHot(List.of(stat(2, 3, 10, 0, 0, null), stat(3, 3, 1, 0, 0, null),
            stat(4, 3, 1, 0, 0, null)), 10)).extracting(HotContent::contentId).containsExactly(2L);
    }

    @Test
    void hotContentIsSortedByMultipleAndCapped() {
        List<ContentStat> stats = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            stats.add(stat(i, 1, 1, 0, 0, null));            // 12 篇冷门内容撑起基线 1
        }
        stats.add(stat(100, 1, 30, 0, 0, null));
        stats.add(stat(101, 1, 50, 0, 0, null));
        stats.add(stat(102, 1, 20, 0, 0, null));

        List<HotContent> hot = HotContentDetector.detectHot(stats, 2);

        assertThat(hot).extracting(HotContent::contentId).containsExactly(101L, 100L);
    }

    @Test
    void potentialContentHasLowPvButHighInteractionOrCompletion() {
        // 频道 1：PV 20, 20, 20, 8（中位数 20）；互动率 0.05, 0.05, 0.05, 0.2（中位数 0.05）；读完率 0.4 ×4
        List<ContentStat> stats = List.of(stat(1, 1, 20, 0, 1, 0.4), stat(2, 1, 20, 0, 1, 0.4), stat(3, 1, 20, 0, 1, 0.4),
            stat(4, 1, 8, 0, 2, 0.4),                          // 互动率 0.25 ≥ 1.5 × 中位数 → 潜力
            stat(5, 1, 3, 0, 3, 0.9));                         // PV < 5 不算

        List<PotentialContent> potential = HotContentDetector.detectPotential(stats, 10);

        assertThat(potential).extracting(PotentialContent::contentId).containsExactly(4L);
        assertThat(potential.get(0).qualityMultiple()).isGreaterThanOrEqualTo(1.5);
    }

    @Test
    void highPvContentIsNeverPotentialEvenIfItsQualityIsHigh() {
        List<ContentStat> stats = List.of(stat(1, 1, 10, 0, 0, 0.3), stat(2, 1, 10, 0, 0, 0.3), stat(3, 1, 100, 0, 50, 0.95));

        assertThat(HotContentDetector.detectPotential(stats, 10)).isEmpty();
    }

    @Test
    void completionRateAloneCanMakeContentPotential() {
        List<ContentStat> stats = List.of(stat(1, 1, 20, 0, 0, 0.3), stat(2, 1, 20, 0, 0, 0.3), stat(3, 1, 9, 0, 0, 0.8));

        assertThat(HotContentDetector.detectPotential(stats, 10)).extracting(PotentialContent::contentId).containsExactly(3L);
    }

    // ---- 告警规则 ----

    private static ChannelRow row(long id, String name, Diagnosis... diagnoses) {
        ChannelPeriod empty = new ChannelPeriod(DistributionMetrics.EMPTY, ContentMetrics.EMPTY, SupplyMetrics.EMPTY, null);
        return new ChannelRow(id, name, "领域频道", empty, empty, List.of(diagnoses));
    }

    @Test
    void channelAlertsKeepCriticalAndWarnDiagnosesAndSkipInfo() {
        List<AlertCandidate> alerts = AlertRules.channelDiagnoses(List.of(
            row(1, "半导体", new Diagnosis("PV_DROP", Diagnosis.CRITICAL, "PV 下降"), new Diagnosis("HIGH_CONCENTRATION", Diagnosis.INFO, "集中")),
            row(2, "光", new Diagnosis("LOW_INTERACTION", Diagnosis.WARN, "互动低")), row(3, "数通")));

        assertThat(alerts).extracting(AlertCandidate::kind).containsExactly("CHANNEL_PV_DROP", "CHANNEL_LOW_INTERACTION");
        assertThat(alerts.get(0).severity()).isEqualTo("CRITICAL");
        assertThat(alerts.get(0).entityId()).isEqualTo(1L);
        assertThat(alerts.get(0).entityName()).isEqualTo("半导体");
        assertThat(alerts.get(0).message()).isEqualTo("PV 下降");
    }

    @Test
    void dauDropIsWarnAboveTwentyPercentAndCriticalAboveFifty() {
        assertThat(AlertRules.dauDrop(100, 100)).isEmpty();
        assertThat(AlertRules.dauDrop(81, 100)).isEmpty();
        assertThat(AlertRules.dauDrop(79, 100).orElseThrow().severity()).isEqualTo("WARN");
        assertThat(AlertRules.dauDrop(40, 100).orElseThrow().severity()).isEqualTo("CRITICAL");
        assertThat(AlertRules.dauDrop(40, 100).orElseThrow().message()).contains("60.0%");
        assertThat(AlertRules.dauDrop(0, 4)).isEmpty();               // 前 7 天日均太小，不下结论
    }

    @Test
    void lowActivationNeedsTenJoinersAndAnActivationBelowThirtyPercent() {
        assertThat(AlertRules.lowActivation(9, 0)).isEmpty();
        assertThat(AlertRules.lowActivation(10, 3)).isEmpty();        // 刚好 30%
        AlertCandidate alert = AlertRules.lowActivation(20, 4).orElseThrow();
        assertThat(alert.kind()).isEqualTo("OVERALL_LOW_ACTIVATION");
        assertThat(alert.message()).contains("20").contains("4").contains("20.0%");
    }

    @Test
    void lowPenetrationComparesWithTheMedianAndIgnoresSmallDepartments() {
        DeptPeriod big = new DeptPeriod(100, 80, 0.8, 0, 0, 0, 0);
        DeptPeriod mid = new DeptPeriod(100, 60, 0.6, 0, 0, 0, 0);
        DeptPeriod low = new DeptPeriod(100, 15, 0.15, 0, 0, 0, 0);      // 四个有渗透率的部门 0.1/0.15/0.6/0.8，中位数 0.375，一半 0.1875 → 触发
        DeptPeriod smallLow = new DeptPeriod(10, 1, 0.1, 0, 0, 0, 0);    // 成员不足 20，不告警（但仍参与中位数）
        DeptPeriod empty = new DeptPeriod(0, 0, null, 0, 0, 0, 0);
        List<DepartmentRow> departments = List.of(new DepartmentRow("甲", big, empty), new DepartmentRow("乙", mid, empty),
            new DepartmentRow("丙", low, empty), new DepartmentRow("丁", smallLow, empty), new DepartmentRow("戊", empty, empty));

        List<AlertCandidate> alerts = AlertRules.lowPenetration(departments);

        assertThat(alerts).extracting(AlertCandidate::entityName).containsExactly("丙");
        assertThat(alerts.get(0).entityType()).isEqualTo(GrowthEntityType.DEPARTMENT);
        assertThat(AlertRules.lowPenetration(List.of())).isEmpty();
    }

    @Test
    void bloggerChurnKeepsTheTopRankedThirty() {
        List<ChurnRisk> risks = new ArrayList<>();
        for (int i = 40; i >= 1; i--) {
            risks.add(new ChurnRisk(i, "博主" + i, i));
        }

        List<AlertCandidate> alerts = AlertRules.bloggerChurn(risks);

        assertThat(alerts).hasSize(30);
        assertThat(alerts.get(0).entityId()).isEqualTo(1L);
        assertThat(alerts.get(29).entityId()).isEqualTo(30L);
        assertThat(alerts.get(0).entityType()).isEqualTo(GrowthEntityType.BLOGGER);
        assertThat(alerts.get(0).message()).contains("第 1 位");
    }

    // ---- 任务进度 ----

    @Test
    void progressIsTheShareOfTheDistanceFromBaselineToTarget() {
        assertThat(TaskProgress.progress(0.02, 0.06, 0.04)).isCloseTo(0.5, within(1e-9));
        assertThat(TaskProgress.progress(0.02, 0.06, 0.08)).isCloseTo(1.5, within(1e-9));      // 超额完成
        assertThat(TaskProgress.progress(0.02, 0.06, 0.01)).isCloseTo(-0.25, within(1e-9));    // 反向变化
        assertThat(TaskProgress.progress(100.0, 50.0, 75.0)).isCloseTo(0.5, within(1e-9));     // 目标比基线小也成立
    }

    @Test
    void progressIsUndefinedWithoutAllThreeValuesOrWhenTargetEqualsBaseline() {
        assertThat(TaskProgress.progress(null, 5.0, 3.0)).isNull();
        assertThat(TaskProgress.progress(1.0, null, 3.0)).isNull();
        assertThat(TaskProgress.progress(1.0, 5.0, null)).isNull();
        assertThat(TaskProgress.progress(5.0, 5.0, 7.0)).isNull();
    }
}
