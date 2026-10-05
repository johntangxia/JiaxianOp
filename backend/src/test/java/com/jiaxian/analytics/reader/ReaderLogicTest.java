package com.jiaxian.analytics.reader;

import com.jiaxian.analytics.pv.Granularity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ReaderLogicTest {

    private static final LocalDate MON = LocalDate.of(2025, 6, 2);   // 周一

    private static Member m(long id, LocalDate join) {
        return new Member(id, "员工" + id, "部门", join);
    }

    private static ActiveDay a(long id, LocalDate day) {
        return new ActiveDay(id, day);
    }

    // ---- 活跃与增长 ----

    @Test
    void dailySeriesCountsDistinctActiveUsersNewMembersAndActiveRate() {
        List<Member> members = List.of(m(1, null), m(2, MON.minusDays(10)), m(3, MON.plusDays(1)));
        List<ActiveDay> days = List.of(a(1, MON), a(2, MON), a(1, MON), a(3, MON.plusDays(1)), a(1, MON.plusDays(1)));

        List<ActivityPoint> series = ActivityAggregator.series(members, days, MON, MON.plusDays(2), Granularity.DAY);

        assertThat(series).extracting(ActivityPoint::periodStart).containsExactly(MON, MON.plusDays(1), MON.plusDays(2));
        assertThat(series).extracting(ActivityPoint::activeUsers).containsExactly(2L, 2L, 0L);
        assertThat(series).extracting(ActivityPoint::newMembers).containsExactly(0L, 1L, 0L);
        assertThat(series).extracting(ActivityPoint::members).containsExactly(2L, 3L, 3L);
        assertThat(series.get(0).activeRate()).isEqualTo(1.0);
        assertThat(series.get(1).activeRate()).isCloseTo(2 / 3.0, within(1e-9));
        assertThat(series.get(2).activeRate()).isZero();
    }

    @Test
    void weeklyBucketsDeduplicateAcrossDaysAndAreClippedToTheRange() {
        List<Member> members = List.of(m(1, null), m(2, null));
        List<ActiveDay> days = List.of(a(1, MON), a(1, MON.plusDays(3)), a(2, MON.plusDays(4)), a(2, MON.minusDays(1)));

        // 区间从周三开始：第一周只包含周三到周日，周一的记录被排除
        List<ActivityPoint> series = ActivityAggregator.series(members, days, MON.plusDays(2), MON.plusDays(9), Granularity.WEEK);

        assertThat(series).extracting(ActivityPoint::periodStart).containsExactly(MON, MON.plusWeeks(1));
        assertThat(series.get(0).activeUsers()).isEqualTo(2);   // 员工 1（周四）和员工 2（周五），周一不算
        assertThat(series.get(1).activeUsers()).isZero();
    }

    @Test
    void membersAndNewMembersRespectTheJoinDate() {
        List<Member> members = List.of(m(1, MON.minusDays(30)), m(2, MON.plusDays(2)), m(3, MON.plusDays(9)));

        List<ActivityPoint> weekly = ActivityAggregator.series(members, List.of(), MON, MON.plusDays(13), Granularity.WEEK);

        assertThat(weekly).extracting(ActivityPoint::newMembers).containsExactly(1L, 1L);
        assertThat(weekly).extracting(ActivityPoint::members).containsExactly(2L, 3L);
    }

    @Test
    void noMembersMeansNoActiveRate() {
        List<ActivityPoint> series = ActivityAggregator.series(List.of(), List.of(), MON, MON, Granularity.DAY);

        assertThat(series.get(0).activeRate()).isNull();
    }

    @Test
    void summaryHasDauWauMauAndStickiness() {
        LocalDate to = LocalDate.of(2025, 6, 30);
        List<Member> members = List.of(m(1, null), m(2, null), m(3, null), m(4, to.plusDays(1)));
        List<ActiveDay> days = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            days.add(a(1, to.minusDays(i)));            // 员工 1 每天活跃
        }
        days.add(a(2, to.minusDays(3)));                // 员工 2 在 WAU 窗口内
        days.add(a(3, to.minusDays(20)));               // 员工 3 只在 MAU 窗口内
        days.add(a(4, to.minusDays(40)));               // 窗口之外

        ActivitySummary summary = ActivityAggregator.summary(members, days, to);

        assertThat(summary.dau()).isEqualTo(1);
        assertThat(summary.wau()).isEqualTo(2);
        assertThat(summary.mau()).isEqualTo(3);
        assertThat(summary.stickiness()).isCloseTo((30 + 1 + 1) / 30.0 / 3, within(1e-9));
        assertThat(summary.members()).isEqualTo(3);   // 员工 4 在 to 之后才加入
    }

    @Test
    void summaryWithoutActivityHasNoStickiness() {
        assertThat(ActivityAggregator.summary(List.of(m(1, null)), List.of(), MON).stickiness()).isNull();
    }

    // ---- 留存同期群 ----

    @Test
    void weeklyCohortRetentionCountsMembersActiveInEachPeriodAfterJoining() {
        // 两个人在 06-02 那周加入，一个人在 06-09 那周加入
        List<Member> members = List.of(m(1, MON), m(2, MON.plusDays(2)), m(3, MON.plusDays(7)));
        List<ActiveDay> days = List.of(
            a(1, MON), a(2, MON.plusDays(2)),                 // 第 0 周都活跃
            a(1, MON.plusDays(8)),                            // 员工 1 第 1 周活跃
            a(3, MON.plusDays(7)), a(3, MON.plusDays(15)));   // 员工 3：第 0 周、第 1 周活跃
        LocalDate dataEnd = MON.plusDays(20);                 // 06-22：06-16~06-22 这周刚好完整，再往后的周还没结束

        List<CohortRow> cohorts = CohortBuilder.build(members, days, MON, MON.plusDays(13), Granularity.WEEK, 3, dataEnd);

        assertThat(cohorts).extracting(CohortRow::cohortStart).containsExactly(MON, MON.plusWeeks(1));
        CohortRow first = cohorts.get(0);
        assertThat(first.size()).isEqualTo(2);
        assertThat(first.retention()).containsExactly(1.0, 0.5, 0.0);      // 第 2 周完整但没人活跃
        CohortRow second = cohorts.get(1);
        assertThat(second.size()).isEqualTo(1);
        assertThat(second.retention()).containsExactly(1.0, 1.0, null);    // 第 2 个周期（06-23 起）还没结束
    }

    @Test
    void cohortsOnlyIncludeMembersWhoJoinedInsideTheRange() {
        List<Member> members = List.of(m(1, MON.minusDays(1)), m(2, null), m(3, MON), m(4, MON.plusDays(14)));

        List<CohortRow> cohorts = CohortBuilder.build(members, List.of(), MON, MON.plusDays(13), Granularity.WEEK, 2, MON.plusDays(60));

        assertThat(cohorts).hasSize(1);
        assertThat(cohorts.get(0).size()).isEqualTo(1);
        assertThat(cohorts.get(0).retention()).containsExactly(0.0, 0.0);
    }

    @Test
    void monthlyCohortsUseCalendarMonths() {
        LocalDate join = LocalDate.of(2025, 3, 20);
        List<ActiveDay> days = List.of(a(1, LocalDate.of(2025, 3, 25)), a(1, LocalDate.of(2025, 5, 2)));

        List<CohortRow> cohorts = CohortBuilder.build(List.of(m(1, join)), days, LocalDate.of(2025, 3, 1),
            LocalDate.of(2025, 3, 31), Granularity.MONTH, 4, LocalDate.of(2025, 5, 31));

        assertThat(cohorts.get(0).cohortStart()).isEqualTo(LocalDate.of(2025, 3, 1));
        assertThat(cohorts.get(0).retention()).containsExactly(1.0, 0.0, 1.0, null);   // 6 月还没结束
    }

    // ---- 漏斗 ----

    private static FunnelFacts facts(long id, LocalDate join, Integer viewHours, Integer interactionHours, Integer postHours, boolean retained) {
        LocalDateTime start = join.atStartOfDay();
        return new FunnelFacts(id, join, viewHours == null ? null : start.plusHours(viewHours),
            interactionHours == null ? null : start.plusHours(interactionHours),
            postHours == null ? null : start.plusHours(postHours), retained);
    }

    @Test
    void funnelCountsEachStepWithinItsWindow() {
        LocalDate join = LocalDate.of(2025, 6, 2);
        List<FunnelFacts> facts = List.of(
            facts(1, join, 5, 30, 24 * 10, true),            // 都满足
            facts(2, join, 24 * 7 - 1, 24 * 7, 24 * 30, false),  // 浏览刚好在窗口内；互动刚好出窗口；发帖刚好出窗口
            facts(3, join, null, null, null, false),        // 什么都没做
            facts(4, join, 24 * 8, 24 * 2, 24 * 29, true));  // 浏览晚了；互动、发帖在窗口内

        List<FunnelStep> steps = FunnelBuilder.build(facts);

        assertThat(steps).extracting(FunnelStep::key).containsExactly("JOINED", "VIEWED", "INTERACTED", "POSTED", "RETAINED");
        assertThat(steps).extracting(FunnelStep::count).containsExactly(4L, 2L, 2L, 2L, 2L);
        assertThat(steps.get(1).rateOfJoined()).isEqualTo(0.5);
        assertThat(steps.get(0).rateOfJoined()).isEqualTo(1.0);
    }

    @Test
    void anEmptyFunnelHasNoRates() {
        List<FunnelStep> steps = FunnelBuilder.build(List.of());

        assertThat(steps).extracting(FunnelStep::count).containsOnly(0L);
        assertThat(steps).extracting(FunnelStep::rateOfJoined).containsOnlyNulls();
    }

    // ---- 角色 ----

    @Test
    void roleOrderIsCreatorInteractorLurkerLoginOnlyInactive() {
        assertThat(ReaderRole.classify(1, 5, 9, true)).isEqualTo(ReaderRole.CREATOR);
        assertThat(ReaderRole.classify(0, 5, 9, true)).isEqualTo(ReaderRole.INTERACTOR);
        assertThat(ReaderRole.classify(0, 0, 9, true)).isEqualTo(ReaderRole.LURKER);
        assertThat(ReaderRole.classify(0, 0, 0, true)).isEqualTo(ReaderRole.LOGIN_ONLY);
        assertThat(ReaderRole.classify(0, 0, 0, false)).isEqualTo(ReaderRole.INACTIVE);
        assertThat(ReaderRole.LURKER.label()).isEqualTo("沉默读者");
    }
}
