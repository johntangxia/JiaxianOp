package com.jiaxian.analytics.reader;

import com.jiaxian.analytics.domain.ActiveActionLog;
import com.jiaxian.analytics.domain.ActiveActionType;
import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.reader.ReaderQueryRepository.Lurker;
import com.jiaxian.analytics.reader.ReaderQueryRepository.LurkerPage;
import com.jiaxian.analytics.reader.ReaderQueryRepository.MemberActivity;
import com.jiaxian.analytics.repository.ActiveActionLogRepository;
import com.jiaxian.analytics.repository.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2019 年夹具（周期 06-01 ~ 06-14），部门分别是"读者测试部A/B"：
 * <pre>
 * e1 创作者（05-01 加入）：06-03 浏览、06-04 登录、06-05 发帖
 * e2 互动者（05-15 加入）：06-03 点赞、06-03 浏览
 * e3 沉默读者（06-03 加入，部门 A）：06-03 浏览×2、06-10 浏览
 * e4 沉默读者（06-03 加入，部门 B）：06-05 浏览
 * e5 仅登录（06-05 加入，部门 A）：06-06 登录
 * e6 不活跃（05-01 加入）；e7 06-20 才加入（区间结束时还不是成员）
 * </pre>
 */
@SpringBootTest
@Transactional
class ReaderQueryRepositoryIT {

    private static final String BATCH = "reader-query-it";
    private static final LocalDate FROM = LocalDate.of(2019, 6, 1);
    private static final LocalDate TO = LocalDate.of(2019, 6, 14);

    @Autowired private ReaderQueryRepository repository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ActiveActionLogRepository activeActionLogRepository;

    private Employee e1, e2, e3, e4, e5, e6, e7;

    private Employee employee(String name, String dept, LocalDate join) {
        return employeeRepository.save(Employee.builder().name(name).dept(dept).communityJoinDate(join).simulationBatchId(BATCH).build());
    }

    private void action(Employee employee, ActiveActionType type, int day, int hour) {
        activeActionLogRepository.save(ActiveActionLog.builder().employeeId(employee.getId()).actionType(type)
            .occurTime(LocalDateTime.of(2019, 6, day, hour, 0)).simulationBatchId(BATCH).build());
    }

    @BeforeEach
    void fixtures() {
        e1 = employee("读者测试员工1", "读者测试部A", LocalDate.of(2019, 5, 1));
        e2 = employee("读者测试员工2", "读者测试部A", LocalDate.of(2019, 5, 15));
        e3 = employee("读者测试员工3", "读者测试部A", LocalDate.of(2019, 6, 3));
        e4 = employee("读者测试员工4", "读者测试部B", LocalDate.of(2019, 6, 3));
        e5 = employee("读者测试员工5", "读者测试部A", LocalDate.of(2019, 6, 5));
        e6 = employee("读者测试员工6", "读者测试部B", LocalDate.of(2019, 5, 1));
        e7 = employee("读者测试员工7", "读者测试部B", LocalDate.of(2019, 6, 20));

        action(e1, ActiveActionType.VIEW, 3, 10);
        action(e1, ActiveActionType.LOGIN, 4, 9);
        action(e1, ActiveActionType.POST, 5, 11);
        action(e2, ActiveActionType.LIKE, 3, 10);
        action(e2, ActiveActionType.VIEW, 3, 11);
        action(e3, ActiveActionType.VIEW, 3, 10);
        action(e3, ActiveActionType.VIEW, 3, 15);
        action(e3, ActiveActionType.VIEW, 10, 10);
        action(e4, ActiveActionType.VIEW, 5, 10);
        action(e5, ActiveActionType.LOGIN, 6, 9);
    }

    private MemberActivity activity(long employeeId) {
        return repository.memberActivity(FROM, TO).stream().filter(a -> a.employeeId() == employeeId).findFirst().orElseThrow();
    }

    @Test
    void membersCarryTheCommunityJoinDate() {
        List<Member> members = repository.members();

        Member found = members.stream().filter(m -> m.id() == e3.getId()).findFirst().orElseThrow();
        assertThat(found.name()).isEqualTo("读者测试员工3");
        assertThat(found.dept()).isEqualTo("读者测试部A");
        assertThat(found.joinDate()).isEqualTo(LocalDate.of(2019, 6, 3));
        assertThat(members).extracting(Member::id).isSorted();
    }

    @Test
    void activeDaysAreDeduplicatedPerEmployeeAndDay() {
        List<ActiveDay> days = repository.activeDays(FROM, TO).stream()
            .filter(d -> d.employeeId() == e3.getId()).toList();

        assertThat(days).extracting(ActiveDay::day).containsExactlyInAnyOrder(LocalDate.of(2019, 6, 3), LocalDate.of(2019, 6, 10));
        assertThat(repository.activeDays(LocalDate.of(2019, 6, 4), LocalDate.of(2019, 6, 4)).stream()
            .filter(d -> d.employeeId() == e1.getId())).hasSize(1);
    }

    @Test
    void memberActivityCountsEachKindOfAction() {
        MemberActivity creator = activity(e1.getId());
        assertThat(creator.posts()).isEqualTo(1);
        assertThat(creator.views()).isEqualTo(1);
        assertThat(creator.interactions()).isZero();
        assertThat(creator.events()).isEqualTo(3);
        assertThat(creator.activeDays()).isEqualTo(3);
        assertThat(creator.lastActive()).isEqualTo(LocalDate.of(2019, 6, 5));

        MemberActivity interactor = activity(e2.getId());
        assertThat(interactor.interactions()).isEqualTo(1);
        assertThat(interactor.views()).isEqualTo(1);

        MemberActivity lurker = activity(e3.getId());
        assertThat(lurker.views()).isEqualTo(3);
        assertThat(lurker.activeDays()).isEqualTo(2);
        assertThat(lurker.lastActive()).isEqualTo(LocalDate.of(2019, 6, 10));

        assertThat(repository.memberActivity(FROM, TO)).extracting(MemberActivity::employeeId)
            .doesNotContain(e6.getId(), e7.getId());   // 没有任何行为的人不出现
    }

    @Test
    void latestActiveDayIsTheDateOfTheLastRecord() {
        assertThat(repository.latestActiveDay()).isPresent();
        assertThat(repository.latestActiveDay().get()).isAfterOrEqualTo(LocalDate.of(2019, 6, 10));
    }

    @Test
    void funnelFactsCoverMembersWhoJoinedInTheRange() {
        List<FunnelFacts> facts = repository.funnelFacts(LocalDate.of(2019, 6, 3), LocalDate.of(2019, 6, 5));

        assertThat(facts).extracting(FunnelFacts::employeeId).containsExactly(e3.getId(), e4.getId(), e5.getId());
        FunnelFacts first = facts.get(0);
        assertThat(first.joinDate()).isEqualTo(LocalDate.of(2019, 6, 3));
        assertThat(first.firstView()).isEqualTo(LocalDateTime.of(2019, 6, 3, 10, 0));
        assertThat(first.firstInteraction()).isNull();
        assertThat(first.firstPost()).isNull();
        assertThat(first.activeInSecondWeek()).isTrue();           // 06-10 在 06-10 ~ 06-16 窗口内
        assertThat(facts.get(1).activeInSecondWeek()).isFalse();
        assertThat(facts.get(2).firstView()).isNull();             // 只登录
        assertThat(facts.get(2).activeInSecondWeek()).isFalse();
    }

    @Test
    void lurkersViewButNeverInteractOrPost() {
        LurkerPage page = repository.lurkers(FROM, TO, null, 10, 0);

        assertThat(page.lurkers()).extracting(Lurker::employeeId).containsExactly(e3.getId(), e4.getId());
        assertThat(page.total()).isEqualTo(2);
        Lurker top = page.lurkers().get(0);
        assertThat(top.name()).isEqualTo("读者测试员工3");
        assertThat(top.dept()).isEqualTo("读者测试部A");
        assertThat(top.joinDate()).isEqualTo(LocalDate.of(2019, 6, 3));
        assertThat(top.views()).isEqualTo(3);
        assertThat(top.activeDays()).isEqualTo(2);
        assertThat(top.lastActive()).isEqualTo(LocalDate.of(2019, 6, 10));
    }

    @Test
    void lurkersCanBeFilteredByDeptAndPaged() {
        assertThat(repository.lurkers(FROM, TO, "读者测试部B", 10, 0).lurkers()).extracting(Lurker::employeeId)
            .containsExactly(e4.getId());
        assertThat(repository.lurkers(FROM, TO, "读者测试部B", 10, 0).total()).isEqualTo(1);

        LurkerPage second = repository.lurkers(FROM, TO, null, 1, 1);
        assertThat(second.total()).isEqualTo(2);
        assertThat(second.lurkers()).extracting(Lurker::employeeId).containsExactly(e4.getId());
        assertThat(repository.lurkers(FROM, TO, null, 1, 5).lurkers()).isEmpty();
    }

    @Test
    void aMemberWhoJoinsAfterTheRangeEndIsNeverAListedLurker() {
        action(e7, ActiveActionType.VIEW, 7, 10);   // 构造一条早于加入日的脏数据

        assertThat(repository.lurkers(FROM, TO, null, 10, 0).lurkers()).extracting(Lurker::employeeId)
            .doesNotContain(e7.getId());
    }

    @Test
    void deptsAreListedOnce() {
        assertThat(repository.depts()).contains("读者测试部A", "读者测试部B").doesNotHaveDuplicates().isSorted();
    }

    @Test
    void loginPostSpanIgnoresViewsAndEndsAtTheLastLoginOrPost() {
        // 夹具里 06-05 有发帖、06-06 有登录；再加一条更晚的浏览，不应改变终点
        action(e3, ActiveActionType.VIEW, 28, 10);
        action(e1, ActiveActionType.LOGIN, 7, 9);

        ReaderQueryRepository.DateSpan span = repository.loginPostSpan().orElseThrow();

        assertThat(span.to()).isAfterOrEqualTo(LocalDate.of(2019, 6, 7));
        assertThat(span.to()).isNotEqualTo(LocalDate.of(2019, 6, 28));
    }
}
