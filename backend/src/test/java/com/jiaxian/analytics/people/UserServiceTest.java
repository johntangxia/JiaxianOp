package com.jiaxian.analytics.people;

import com.jiaxian.analytics.people.PeopleQueryRepository.DeptDomainViews;
import com.jiaxian.analytics.people.PeopleQueryRepository.DeptReaders;
import com.jiaxian.analytics.people.PeopleQueryRepository.KolEntry;
import com.jiaxian.analytics.people.PeopleQueryRepository.LabelCount;
import com.jiaxian.analytics.people.PeopleQueryRepository.ReadingHabits;
import com.jiaxian.analytics.reader.ActiveDay;
import com.jiaxian.analytics.reader.Member;
import com.jiaxian.analytics.reader.ReaderQueryRepository;
import com.jiaxian.analytics.reader.ReaderQueryRepository.MemberActivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2025, 9, 30);

    private final PeopleQueryRepository people = mock(PeopleQueryRepository.class);
    private final ReaderQueryRepository readers = mock(ReaderQueryRepository.class);
    private final AsOfResolver resolver = new AsOfResolver(readers,
        Clock.fixed(LocalDate.of(2025, 12, 1).atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));
    private final UserService service = new UserService(people, readers, resolver);

    private static Member member(long id, String dept, LocalDate join) {
        return new Member(id, "员工" + id, dept, join);
    }

    private static LocalDateTime at(int month, int day) {
        return LocalDateTime.of(2025, month, day, 10, 0);
    }

    private static LifetimeFacts facts(long id, LocalDateTime first, LocalDateTime last) {
        return new LifetimeFacts(id, first, null, null, null, 0, last);
    }

    /**
     * 统计日 09-30（W = 09-01~09-30，P = 08-02~08-31），上一个时点 08-31：
     * 1 活跃（上个时点：回流）；2 沉默（上个时点：回流）；3 流失；4 未激活；5 新成员（09-20 加入，此前非成员）；
     * 6 回流（首次被激活，上个时点：未激活）；7 还没加入。
     */
    @BeforeEach
    void fixtures() {
        LocalDate old = LocalDate.of(2024, 1, 1);
        when(readers.members()).thenReturn(List.of(member(1, "甲", old), member(2, "甲", old), member(3, "乙", old),
            member(4, "乙", old), member(5, "乙", LocalDate.of(2025, 9, 20)), member(6, "甲", old),
            member(7, "甲", LocalDate.of(2025, 10, 5))));
        when(readers.depts()).thenReturn(List.of("乙", "甲"));
        when(readers.activeDays(any(), any())).thenReturn(List.of(new ActiveDay(1, LocalDate.of(2025, 9, 20)),
            new ActiveDay(1, LocalDate.of(2025, 8, 20)), new ActiveDay(2, LocalDate.of(2025, 8, 21)),
            new ActiveDay(6, LocalDate.of(2025, 9, 25))));
        when(people.lifetimeFacts(AS_OF)).thenReturn(List.of(facts(1, at(3, 1), at(9, 20)), facts(2, at(8, 21), at(8, 21)),
            facts(3, at(5, 1), at(5, 1)), facts(6, at(9, 25), at(9, 25))));
        when(readers.memberActivity(any(), any())).thenReturn(List.of(
            new MemberActivity(1, 2, 10, 100, 130, 30, LocalDate.of(2025, 9, 29)),
            new MemberActivity(2, 0, 0, 20, 20, 5, LocalDate.of(2025, 8, 21)),
            new MemberActivity(6, 0, 0, 5, 5, 1, LocalDate.of(2025, 9, 25))));
        when(people.latestKolDate(any())).thenReturn(Optional.empty());
    }

    // ---- 生命周期 ----

    @Test
    void lifecycleCountsCurrentAndPreviousStagesAndTheTransitionMatrix() {
        UserLifecycleReport report = service.lifecycle(AS_OF);

        assertThat(report.members()).isEqualTo(6);
        assertThat(report.previousMembers()).isEqualTo(5);
        assertThat(report.previousAsOf()).isEqualTo(LocalDate.of(2025, 8, 31));
        assertThat(report.stages()).extracting(UserLifecycleReport.StageRow::stage, UserLifecycleReport.StageRow::count,
            UserLifecycleReport.StageRow::previousCount).containsExactly(
            org.assertj.core.groups.Tuple.tuple(LifecycleStage.NEW, 1L, 0L),
            org.assertj.core.groups.Tuple.tuple(LifecycleStage.ACTIVE, 1L, 0L),
            org.assertj.core.groups.Tuple.tuple(LifecycleStage.RETURNING, 1L, 2L),
            org.assertj.core.groups.Tuple.tuple(LifecycleStage.LAPSING, 1L, 0L),
            org.assertj.core.groups.Tuple.tuple(LifecycleStage.CHURNED, 1L, 1L),
            org.assertj.core.groups.Tuple.tuple(LifecycleStage.DORMANT, 1L, 2L));
        assertThat(report.stages().get(0).share()).isEqualTo(1 / 6.0);

        // 流转矩阵：每个现有成员只出现一次；每行的合计 = 上个时点该阶段的人数（最后一行 = 此前非成员）
        assertThat(report.transitions().stream().mapToLong(UserLifecycleReport.TransitionView::total).sum()).isEqualTo(6);
        UserLifecycleReport.TransitionView returning = report.transitions().stream()
            .filter(t -> t.from() == LifecycleStage.RETURNING).findFirst().orElseThrow();
        assertThat(returning.total()).isEqualTo(2);
        assertThat(returning.to()).filteredOn(c -> c.count() > 0).extracting(UserLifecycleReport.Cell::stage)
            .containsExactly(LifecycleStage.ACTIVE, LifecycleStage.LAPSING);
        UserLifecycleReport.TransitionView fresh = report.transitions().get(report.transitions().size() - 1);
        assertThat(fresh.from()).isNull();
        assertThat(fresh.fromLabel()).isEqualTo("此前非成员");
        assertThat(fresh.total()).isEqualTo(1);
        for (LifecycleStage stage : LifecycleStage.values()) {
            long previous = report.stages().get(stage.ordinal()).previousCount();
            long row = report.transitions().get(stage.ordinal()).total();
            assertThat(row).as(stage.name()).isEqualTo(previous);
        }
    }

    @Test
    void asOfDefaultsToTheLatestLoginOrPostDay() {
        when(readers.loginPostSpan()).thenReturn(Optional.of(new ReaderQueryRepository.DateSpan(LocalDate.of(2025, 1, 1), AS_OF)));
        when(people.lifetimeFacts(AS_OF)).thenReturn(List.of());

        assertThat(service.lifecycle(null).asOf()).isEqualTo(AS_OF);
    }

    // ---- 价值分层 ----

    @Test
    void valueSegmentsCoverEveryMemberExactlyOnce() {
        UserValueReport report = service.value(AS_OF);

        assertThat(report.members()).isEqualTo(6);
        assertThat(report.segments().stream().mapToLong(UserValueReport.SegmentRow::count).sum()).isEqualTo(6);
        UserValueReport.SegmentRow core = report.segments().get(0);
        assertThat(core.segment()).isEqualTo(ValueSegment.CORE);
        // 榜内百分位：只有 1、2、6 号有数据，它们在全员里的 R、M 都排在前面（3、4、5 号全是 1 分）
        assertThat(core.count()).isEqualTo(3);
        assertThat(core.avgEngagement()).isEqualTo((150.0 + 20 + 5) / 3);   // 1 号：浏览 100 + 3×互动 10 + 10×发帖 2 = 150
        assertThat(core.avgActiveDays()).isEqualTo((30.0 + 5 + 1) / 3);
        UserValueReport.SegmentRow sleeping = report.segments().get(4);
        assertThat(sleeping.count()).isEqualTo(3);                          // 3、4、5 号没有任何行为
        assertThat(sleeping.avgEngagement()).isZero();
        assertThat(report.segments().get(2).count()).isZero();
        assertThat(report.segments().get(2).avgEngagement()).isNull();
    }

    // ---- 用户列表 ----

    @Test
    void listDefaultsToMostRecentlyActiveFirstWithMissingLast() {
        UserListReport report = service.list(AS_OF, null, null, null, null, null, null, null);

        assertThat(report.total()).isEqualTo(6);
        assertThat(report.users()).extracting(UserListReport.UserRow::employeeId).startsWith(1L, 6L, 2L);
        assertThat(report.users().get(0).stageLabel()).isEqualTo("活跃");
        assertThat(report.users().get(0).valueLabel()).isEqualTo("核心");
        assertThat(report.users().get(0).lastActive()).isEqualTo(LocalDate.of(2025, 9, 29));
        assertThat(report.users().get(5).lastActive()).isNull();
        assertThat(report.depts()).containsExactly("乙", "甲");
    }

    @Test
    void listFiltersByStageValueDeptAndName() {
        assertThat(service.list(AS_OF, "ACTIVE", null, null, null, null, null, null).users())
            .extracting(UserListReport.UserRow::employeeId).containsExactly(1L);
        assertThat(service.list(AS_OF, "dormant", null, null, null, null, null, null).users())
            .extracting(UserListReport.UserRow::employeeId).containsExactly(4L);
        assertThat(service.list(AS_OF, null, "CORE", null, null, null, null, null).users())
            .extracting(UserListReport.UserRow::employeeId).containsExactly(1L, 6L, 2L);
        assertThat(service.list(AS_OF, null, "sleeping", null, null, null, null, null).users())
            .extracting(UserListReport.UserRow::employeeId).containsExactlyInAnyOrder(3L, 4L, 5L);
        assertThat(service.list(AS_OF, null, null, "乙", null, null, null, null).total()).isEqualTo(3);
        assertThat(service.list(AS_OF, null, null, null, "员工6", null, null, null).users())
            .extracting(UserListReport.UserRow::employeeId).containsExactly(6L);
        assertThat(service.list(AS_OF, "ACTIVE", null, "乙", null, null, null, null).total()).isZero();
    }

    @Test
    void listSortsByEachMetricAndPages() {
        assertThat(service.list(AS_OF, null, null, null, null, "VIEWS", 2, null).users())
            .extracting(UserListReport.UserRow::employeeId).containsExactly(1L, 2L);
        assertThat(service.list(AS_OF, null, null, null, null, "posts", 1, null).users().get(0).employeeId()).isEqualTo(1L);
        assertThat(service.list(AS_OF, null, null, null, null, "INTERACTIONS", 1, null).users().get(0).interactions()).isEqualTo(10);
        assertThat(service.list(AS_OF, null, null, null, null, "ACTIVE_DAYS", 1, null).users().get(0).activeDays()).isEqualTo(30);

        UserListReport page = service.list(AS_OF, null, null, null, null, "VIEWS", 2, 2);
        assertThat(page.users()).extracting(UserListReport.UserRow::employeeId).containsExactly(6L, 3L);   // 同为 0 的按员工 id
        assertThat(page.total()).isEqualTo(6);
        assertThat(page.limit()).isEqualTo(2);
        assertThat(page.offset()).isEqualTo(2);
    }

    @Test
    void listRejectsBadParameters() {
        assertThatThrownBy(() -> service.list(AS_OF, "SLEEPY", null, null, null, null, null, null)).isInstanceOf(InvalidPeopleQueryException.class);
        assertThatThrownBy(() -> service.list(AS_OF, null, "GOLD", null, null, null, null, null)).isInstanceOf(InvalidPeopleQueryException.class);
        assertThatThrownBy(() -> service.list(AS_OF, null, null, null, null, "NAME", null, null)).isInstanceOf(InvalidPeopleQueryException.class);
        assertThatThrownBy(() -> service.list(AS_OF, null, null, null, null, null, 0, null)).isInstanceOf(InvalidPeopleQueryException.class);
        assertThatThrownBy(() -> service.list(AS_OF, null, null, null, null, null, 101, null)).isInstanceOf(InvalidPeopleQueryException.class);
        assertThatThrownBy(() -> service.list(AS_OF, null, null, null, null, null, null, -1)).isInstanceOf(InvalidPeopleQueryException.class);
    }

    // ---- 兴趣与跨领域 ----

    @Test
    void interestsShowOwnDomainShareAndTopOtherDomains() {
        when(people.deptReaders(any(), any())).thenReturn(List.of(new DeptReaders("甲", 3, 100), new DeptReaders("乙", 1, 10)));
        when(people.deptDomainViews(any(), any())).thenReturn(List.of(
            new DeptDomainViews("甲", "甲", 40), new DeptDomainViews("甲", "乙", 30), new DeptDomainViews("甲", "丙", 20),
            new DeptDomainViews("甲", "丁", 5), new DeptDomainViews("甲", "戊", 5), new DeptDomainViews("乙", "乙", 10)));

        UserInterestReport report = service.interests(AS_OF, 30);

        assertThat(report.days()).isEqualTo(30);
        assertThat(report.depts()).extracting(UserInterestReport.DeptInterest::dept).containsExactly("甲", "乙");   // 按浏览数
        UserInterestReport.DeptInterest jia = report.depts().get(0);
        assertThat(jia.members()).isEqualTo(3);        // 1、2、6
        assertThat(jia.readers()).isEqualTo(3);
        assertThat(jia.ownShare()).isEqualTo(0.4);
        assertThat(jia.topOthers()).extracting(UserInterestReport.DomainShare::domain).containsExactly("乙", "丙", "丁");
        assertThat(jia.topOthers().get(0).share()).isEqualTo(0.3);
        assertThat(report.depts().get(1).topOthers()).isEmpty();
        assertThat(report.depts().get(1).ownShare()).isEqualTo(1.0);
    }

    @Test
    void interestsKeepDeptsWithoutReadersAndRejectBadDays() {
        when(people.deptReaders(any(), any())).thenReturn(List.of());
        when(people.deptDomainViews(any(), any())).thenReturn(List.of());

        UserInterestReport report = service.interests(AS_OF, null);

        assertThat(report.days()).isEqualTo(90);
        assertThat(report.depts()).hasSize(2);
        assertThat(report.depts()).allSatisfy(d -> {
            assertThat(d.views()).isZero();
            assertThat(d.ownShare()).isNull();
        });
        assertThatThrownBy(() -> service.interests(AS_OF, 6)).isInstanceOf(InvalidPeopleQueryException.class);
        assertThatThrownBy(() -> service.interests(AS_OF, 181)).isInstanceOf(InvalidPeopleQueryException.class);
        assertThat(service.interests(AS_OF, 7).days()).isEqualTo(7);
        assertThat(service.interests(AS_OF, 180).days()).isEqualTo(180);
    }

    // ---- 用户 360 ----

    @Test
    void profileOfAMissingEmployeeIs404() {
        when(people.member(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.profile(99, AS_OF)).isInstanceOf(PeopleNotFoundException.class);
    }

    @Test
    void profileAssemblesBasicRecentTimelineReadingAndKol() {
        when(people.member(1)).thenReturn(Optional.of(member(1, "甲", LocalDate.of(2024, 1, 1))));
        when(people.latestKolDate(AS_OF)).thenReturn(Optional.of(LocalDate.of(2025, 9, 28)));
        when(people.kolEntry(1, LocalDate.of(2025, 9, 28))).thenReturn(Optional.of(new KolEntry(LocalDate.of(2025, 9, 28), 3, "STAR_KOL")));
        when(people.lifetimeFacts(AS_OF)).thenReturn(List.of(new LifetimeFacts(1, at(3, 1), at(3, 2), at(3, 3), at(3, 4), 4, at(9, 20))));
        when(people.weeklyActivity(anyLong(), any(), any())).thenReturn(List.of(
            new PeopleQueryRepository.WeekActivity(LocalDate.of(2025, 9, 22), 3, 10, 2, 1)));
        when(people.readingHabits(anyLong(), any(), any())).thenReturn(new ReadingHabits(10, 4, 45.0, 0.5));
        when(people.viewedChannels(anyLong(), any(), any(), anyInt())).thenReturn(List.of(new LabelCount("甲", 6), new LabelCount("乙", 4)));
        when(people.viewedTags(anyLong(), any(), any(), anyInt())).thenReturn(List.of(new LabelCount("ai", 5)));
        when(people.viewSources(anyLong(), any(), any())).thenReturn(List.of(new LabelCount("SHARE", 7), new LabelCount("ENTRY", 3)));
        when(people.follows(1)).thenReturn(List.of());
        when(people.points(anyLong(), any(), any())).thenReturn(new PeopleQueryRepository.Points(40, 60));
        when(people.recentEvents(anyLong(), any(), anyInt())).thenReturn(List.of());

        UserProfile profile = service.profile(1, AS_OF);

        assertThat(profile.basic().name()).isEqualTo("员工1");
        assertThat(profile.basic().stage()).isEqualTo(LifecycleStage.ACTIVE);
        assertThat(profile.basic().previousStageLabel()).isEqualTo("回流");
        assertThat(profile.basic().valueLabel()).isEqualTo("核心");
        assertThat(profile.basic().creatorStage()).isEqualTo(CreatorStage.KOL);
        assertThat(profile.basic().daysInCommunity()).isEqualTo(java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(2024, 1, 1), AS_OF));
        assertThat(profile.hasPosts()).isTrue();
        assertThat(profile.kol().rank()).isEqualTo(3);
        assertThat(profile.kol().segmentName()).isEqualTo("明星KOL");
        assertThat(profile.recent().role()).isEqualTo("CREATOR");
        assertThat(profile.recent().posts()).isEqualTo(2);

        assertThat(profile.timeline()).hasSize(12);
        assertThat(profile.timeline().get(11).weekStart()).isEqualTo(LocalDate.of(2025, 9, 29));
        assertThat(profile.timeline().get(10).views()).isEqualTo(10);        // 09-22 那周
        assertThat(profile.timeline().get(0).views()).isZero();               // 没有行为的周补零

        assertThat(profile.reading().channels().get(0).share()).isEqualTo(0.6);
        assertThat(profile.reading().sources()).extracting(UserProfile.Share::label).containsExactly("ENTRY", "SEARCH", "SHARE", "OTHER");
        assertThat(profile.reading().sources().get(2).share()).isEqualTo(0.7);
        assertThat(profile.reading().sources().get(1).count()).isZero();
        assertThat(profile.reading().videoShare()).isEqualTo(0.4);
        assertThat(profile.points().total()).isEqualTo(100);
    }

    @Test
    void profileOfANewMemberWithoutActivityHasEmptySections() {
        when(people.member(5)).thenReturn(Optional.of(member(5, "乙", LocalDate.of(2025, 9, 20))));
        when(people.weeklyActivity(anyLong(), any(), any())).thenReturn(List.of());
        when(people.readingHabits(anyLong(), any(), any())).thenReturn(new ReadingHabits(0, 0, null, null));
        when(people.viewedChannels(anyLong(), any(), any(), anyInt())).thenReturn(List.of());
        when(people.viewedTags(anyLong(), any(), any(), anyInt())).thenReturn(List.of());
        when(people.viewSources(anyLong(), any(), any())).thenReturn(List.of());
        when(people.follows(5)).thenReturn(List.of());
        when(people.points(anyLong(), any(), any())).thenReturn(new PeopleQueryRepository.Points(0, 0));
        when(people.recentEvents(anyLong(), any(), anyInt())).thenReturn(List.of());

        UserProfile profile = service.profile(5, AS_OF);

        assertThat(profile.basic().stage()).isEqualTo(LifecycleStage.NEW);
        assertThat(profile.basic().previousStage()).isNull();
        assertThat(profile.basic().creatorStage()).isEqualTo(CreatorStage.INACTIVE);
        assertThat(profile.recent().role()).isEqualTo("INACTIVE");
        assertThat(profile.recent().lastActive()).isNull();
        assertThat(profile.hasPosts()).isFalse();
        assertThat(profile.kol()).isNull();
        assertThat(profile.reading().videoShare()).isNull();
        assertThat(profile.reading().sources()).allSatisfy(s -> assertThat(s.count()).isZero());
    }
}
