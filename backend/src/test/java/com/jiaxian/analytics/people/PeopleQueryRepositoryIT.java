package com.jiaxian.analytics.people;

import com.jiaxian.analytics.domain.*;
import com.jiaxian.analytics.people.PeopleQueryRepository.*;
import com.jiaxian.analytics.reader.Member;
import com.jiaxian.analytics.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2019 年夹具，部门"人员测试部A/B"：
 * <pre>
 * e1(A)：02-01 发帖、05-20 浏览、05-21 点赞、06-03 发帖、06-05 发帖、06-06 登录；e2(A)：06-04 浏览、06-04 发帖×2；
 * e3(B)：06-08 浏览（06-11 还有一次浏览，晚于统计日）；e4 没有任何行为
 * 账号 A1（个人，成员 e1）、A2（团队，成员 e2、e3）；内容 c1（A1，06-02 发布，作者 e1，领域甲，文章，标签 ai/芯片）、
 *   c2（A2，06-03 发布，作者 e2+e3，领域乙，视频，标签 ai）、c0（A1，05-01 发布，窗口外）
 * 关注：e3→A1 06-04、e4→A1 06-05、e4→A2 06-20（窗口外）、e1→A2 06-06；e2→A1 06-01 一直在、e2→A2 06-03（06-09 取关）
 * 浏览：e2 看 c1×2（入口 60 读完、搜索 30 未读完）和 c2 一次（来源为空、无停留）；e1 看 c1 一次；e3 看 c1 一次
 * </pre>
 */
@SpringBootTest
@Transactional
class PeopleQueryRepositoryIT {

    private static final String BATCH = "people-query-it";
    private static final LocalDate FROM = LocalDate.of(2019, 6, 1);
    private static final LocalDate TO = LocalDate.of(2019, 6, 10);

    @Autowired private PeopleQueryRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountMemberRepository accountMemberRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private ContentAuthorRepository contentAuthorRepository;
    @Autowired private ContentTagRepository contentTagRepository;
    @Autowired private ActiveActionLogRepository activeActionLogRepository;
    @Autowired private PvLogRepository pvLogRepository;
    @Autowired private FollowRelationRepository followRelationRepository;
    @Autowired private PointsLedgerRepository pointsLedgerRepository;

    private Employee e1, e2, e3, e4;
    private Account a1, a2;
    private Channel jia, yi;

    private static LocalDateTime t(int month, int day, int hour) {
        return LocalDateTime.of(2019, month, day, hour, 0);
    }

    private Employee employee(String name, String dept) {
        return employeeRepository.save(Employee.builder().name(name).dept(dept).communityJoinDate(LocalDate.of(2019, 1, 1))
            .simulationBatchId(BATCH).build());
    }

    private Account account(String name, AccountType type, Employee... members) {
        Account account = accountRepository.save(Account.builder().name(name).type(type).simulationBatchId(BATCH).build());
        for (Employee member : members) {
            accountMemberRepository.save(AccountMember.builder().accountId(account.getId()).employeeId(member.getId())
                .simulationBatchId(BATCH).build());
        }
        return account;
    }

    private Content content(Channel channel, Account account, ContentType type, LocalDateTime publish, String[] tags, Employee... authors) {
        Content content = contentRepository.save(Content.builder().type(type).channelId(channel.getId()).accountId(account.getId())
            .publishTime(publish).popularityWeight(0.5).simulationBatchId(BATCH).build());
        for (Employee author : authors) {
            contentAuthorRepository.save(ContentAuthor.builder().contentId(content.getId()).employeeId(author.getId())
                .simulationBatchId(BATCH).build());
        }
        for (String tag : tags) {
            contentTagRepository.save(ContentTag.builder().contentId(content.getId()).tag(tag).simulationBatchId(BATCH).build());
        }
        return content;
    }

    private void action(Employee employee, ActiveActionType type, int month, int day, int hour) {
        activeActionLogRepository.save(ActiveActionLog.builder().employeeId(employee.getId()).actionType(type)
            .occurTime(t(month, day, hour)).simulationBatchId(BATCH).build());
    }

    private void view(Content content, Employee viewer, int day, ViewSource source, Integer dwell, boolean completed) {
        pvLogRepository.save(PvLog.builder().contentId(content.getId()).viewerEmployeeId(viewer.getId()).viewTime(t(6, day, 10))
            .sourceType(source).dwellSeconds(dwell).completed(dwell == null ? null : completed).simulationBatchId(BATCH).build());
    }

    private void follow(Employee follower, Account account, LocalDateTime followTime, LocalDateTime unfollowTime) {
        followRelationRepository.save(FollowRelation.builder().followerEmployeeId(follower.getId()).accountId(account.getId())
            .followTime(followTime).unfollowTime(unfollowTime).simulationBatchId(BATCH).build());
    }

    private void points(Employee employee, PointsCategory category, int points, LocalDateTime time) {
        pointsLedgerRepository.save(PointsLedger.builder().employeeId(employee.getId()).actionType("TEST").category(category)
            .points(points).occurTime(time).simulationBatchId(BATCH).build());
    }

    private void kol(LocalDate asOf, Employee employee, int rank, String segment) {
        jdbc.update("INSERT INTO blogger_rfm_score (as_of_date, employee_id, rank_no, rolling_points_12m, recency_days, frequency,"
            + " monetary, r_score, f_score, m_score, segment_label, churn_alert) VALUES (?, ?, ?, 100, 1, 1, 100, 3, 3, 3, ?, FALSE)",
            Timestamp.valueOf(asOf.atStartOfDay()), employee.getId(), rank, segment);
    }

    @BeforeEach
    void fixtures() {
        e1 = employee("人员测试员工1", "人员测试部A");
        e2 = employee("人员测试员工2", "人员测试部A");
        e3 = employee("人员测试员工3", "人员测试部B");
        e4 = employee("人员测试员工4", "人员测试部B");
        a1 = account("人员测试个人号", AccountType.PERSONAL, e1);
        a2 = account("人员测试团队号", AccountType.TEAM, e2, e3);
        jia = channelRepository.save(Channel.builder().name("人员测试领域甲").category("领域频道").build());
        yi = channelRepository.save(Channel.builder().name("人员测试领域乙").category("领域频道").build());
        Content c0 = content(jia, a1, ContentType.ARTICLE, t(5, 1, 9), new String[] {}, e1);
        Content c1 = content(jia, a1, ContentType.ARTICLE, t(6, 2, 9), new String[] {"ai", "芯片"}, e1);
        Content c2 = content(yi, a2, ContentType.VIDEO, t(6, 3, 9), new String[] {"ai"}, e2, e3);

        action(e1, ActiveActionType.POST, 2, 1, 9);
        action(e1, ActiveActionType.VIEW, 5, 20, 10);
        action(e1, ActiveActionType.LIKE, 5, 21, 10);
        action(e1, ActiveActionType.POST, 6, 3, 9);
        action(e1, ActiveActionType.POST, 6, 5, 9);
        action(e1, ActiveActionType.LOGIN, 6, 6, 9);
        action(e2, ActiveActionType.VIEW, 6, 4, 10);
        action(e2, ActiveActionType.POST, 6, 4, 11);
        action(e2, ActiveActionType.POST, 6, 4, 12);
        action(e3, ActiveActionType.VIEW, 6, 8, 10);
        action(e3, ActiveActionType.VIEW, 6, 11, 10);

        follow(e3, a1, t(6, 4, 9), null);
        follow(e4, a1, t(6, 5, 9), null);
        follow(e4, a2, t(6, 20, 9), null);
        follow(e1, a2, t(6, 6, 9), null);
        follow(e2, a1, t(6, 1, 9), null);
        follow(e2, a2, t(6, 3, 9), t(6, 9, 9));

        view(c1, e2, 4, ViewSource.ENTRY, 60, true);
        view(c1, e2, 5, ViewSource.SEARCH, 30, false);
        view(c2, e2, 6, null, null, false);
        view(c1, e1, 6, ViewSource.SHARE, 20, true);
        view(c1, e3, 8, ViewSource.SHARE, 20, true);

        points(e2, PointsCategory.ACTIVE, 10, t(6, 4, 9));
        points(e2, PointsCategory.ACTIVE, 5, t(6, 5, 9));
        points(e2, PointsCategory.PASSIVE, 7, t(6, 6, 9));
        points(e2, PointsCategory.PASSIVE, 99, t(5, 1, 9));

        kol(LocalDate.of(2019, 6, 1), e1, 1, "STAR_KOL");
        kol(LocalDate.of(2019, 6, 1), e2, 2, "STEADY");
        kol(LocalDate.of(2019, 6, 8), e1, 2, "STEADY");
        kol(LocalDate.of(2019, 6, 8), e2, 1, "STAR_KOL");
    }

    // ---- 创作者 ----

    @Test
    void postDaysCountPostsPerEmployeeAndDay() {
        List<PostDay> days = repository.postDays(FROM, TO).stream()
            .filter(d -> d.employeeId() == e1.getId() || d.employeeId() == e2.getId()).toList();

        assertThat(days).extracting(PostDay::employeeId, PostDay::day, PostDay::posts).containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(e1.getId(), LocalDate.of(2019, 6, 3), 1L),
            org.assertj.core.groups.Tuple.tuple(e1.getId(), LocalDate.of(2019, 6, 5), 1L),
            org.assertj.core.groups.Tuple.tuple(e2.getId(), LocalDate.of(2019, 6, 4), 2L));
    }

    @Test
    void firstPostDayLooksAtTheWholeHistory() {
        Map<Long, LocalDate> first = repository.firstPostDays();

        assertThat(first.get(e1.getId())).isEqualTo(LocalDate.of(2019, 2, 1));
        assertThat(first.get(e2.getId())).isEqualTo(LocalDate.of(2019, 6, 4));
        assertThat(first).doesNotContainKeys(e3.getId(), e4.getId());
    }

    @Test
    void lifetimeFactsAreCumulativeUpToTheAsOfDay() {
        List<LifetimeFacts> facts = repository.lifetimeFacts(TO);

        LifetimeFacts one = facts.stream().filter(f -> f.employeeId() == e1.getId()).findFirst().orElseThrow();
        assertThat(one.firstActivity()).isEqualTo(t(2, 1, 9));
        assertThat(one.firstView()).isEqualTo(t(5, 20, 10));
        assertThat(one.firstInteraction()).isEqualTo(t(5, 21, 10));
        assertThat(one.firstPost()).isEqualTo(t(2, 1, 9));
        assertThat(one.recentPosts()).isEqualTo(2);          // 02-01 的发帖早于近 90 天
        assertThat(one.lastActivity()).isEqualTo(t(6, 6, 9));

        LifetimeFacts three = facts.stream().filter(f -> f.employeeId() == e3.getId()).findFirst().orElseThrow();
        assertThat(three.lastActivity()).isEqualTo(t(6, 8, 10));     // 06-11 的浏览晚于统计日
        assertThat(three.firstPost()).isNull();
        assertThat(three.firstInteraction()).isNull();
        assertThat(facts).extracting(LifetimeFacts::employeeId).doesNotContain(e4.getId());
    }

    @Test
    void newFollowersCountEveryMemberOfTheAccount() {
        Map<Long, Long> followers = repository.newFollowersByEmployee(FROM, TO);

        assertThat(followers.get(e1.getId())).isEqualTo(3);      // A1：e2（06-01）、e3、e4
        assertThat(followers.get(e2.getId())).isEqualTo(2);      // A2：e2 自己（06-03）、e1；e4 的关注在窗口外
        assertThat(followers.get(e3.getId())).isEqualTo(2);      // 团队账号共享：每位成员都计完整数
        assertThat(followers).doesNotContainKey(e4.getId());
    }

    @Test
    void accountTypeSupplySplitsPersonalFromTeam() {
        List<AccountTypeFigures> split = repository.accountTypeSupply(FROM, TO).stream()
            .filter(f -> f.posts() > 0).toList();

        assertThat(split).extracting(AccountTypeFigures::type, AccountTypeFigures::posts, AccountTypeFigures::creators)
            .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("PERSONAL", 1L, 1L),
                org.assertj.core.groups.Tuple.tuple("TEAM", 1L, 2L));
        assertThat(repository.accountTypes()).containsEntry(a1.getId(), "PERSONAL").containsEntry(a2.getId(), "TEAM");
    }

    @Test
    void kolLookupsUseTheLatestRebuildNotAfterTheAsOfDay() {
        assertThat(repository.latestKolDate(LocalDate.of(2019, 6, 10))).contains(LocalDate.of(2019, 6, 8));
        assertThat(repository.latestKolDate(LocalDate.of(2019, 6, 7))).contains(LocalDate.of(2019, 6, 1));
        assertThat(repository.latestKolDate(LocalDate.of(2018, 1, 1))).isEmpty();

        assertThat(repository.starKols(LocalDate.of(2019, 6, 8))).containsExactly(e2.getId());
        assertThat(repository.kolEntry(e1.getId(), LocalDate.of(2019, 6, 8)))
            .contains(new KolEntry(LocalDate.of(2019, 6, 8), 2, "STEADY"));
        assertThat(repository.kolEntry(e3.getId(), LocalDate.of(2019, 6, 8))).isEmpty();
    }

    // ---- 用户 ----

    @Test
    void memberLookupReturnsNameDeptAndJoinDate() {
        Member member = repository.member(e3.getId()).orElseThrow();

        assertThat(member.name()).isEqualTo("人员测试员工3");
        assertThat(member.dept()).isEqualTo("人员测试部B");
        assertThat(member.joinDate()).isEqualTo(LocalDate.of(2019, 1, 1));
        assertThat(repository.member(-1)).isEmpty();
    }

    @Test
    void weeklyActivityGroupsByWeekStartingMonday() {
        List<WeekActivity> weeks = repository.weeklyActivity(e1.getId(), LocalDate.of(2019, 5, 13), TO);

        assertThat(weeks).extracting(WeekActivity::weekStart).containsExactly(LocalDate.of(2019, 5, 20), LocalDate.of(2019, 6, 3));
        assertThat(weeks.get(0).views()).isEqualTo(1);
        assertThat(weeks.get(0).interactions()).isEqualTo(1);
        assertThat(weeks.get(1).posts()).isEqualTo(2);
        assertThat(weeks.get(1).logins()).isEqualTo(1);
    }

    @Test
    void readingPreferencesCoverChannelsTagsSourcesAndHabits() {
        assertThat(repository.viewedChannels(e2.getId(), FROM, TO, 6)).extracting(LabelCount::label, LabelCount::count)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("人员测试领域甲", 2L), org.assertj.core.groups.Tuple.tuple("人员测试领域乙", 1L));
        assertThat(repository.viewedTags(e2.getId(), FROM, TO, 8)).extracting(LabelCount::label, LabelCount::count)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("ai", 3L), org.assertj.core.groups.Tuple.tuple("芯片", 2L));
        assertThat(repository.viewSources(e2.getId(), FROM, TO)).extracting(LabelCount::label, LabelCount::count)
            .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("ENTRY", 1L), org.assertj.core.groups.Tuple.tuple("SEARCH", 1L),
                org.assertj.core.groups.Tuple.tuple("OTHER", 1L));

        ReadingHabits habits = repository.readingHabits(e2.getId(), FROM, TO);
        assertThat(habits.views()).isEqualTo(3);
        assertThat(habits.videoViews()).isEqualTo(1);
        assertThat(habits.avgDwellSeconds()).isEqualTo(45.0);
        assertThat(habits.completionRate()).isEqualTo(0.5);

        ReadingHabits none = repository.readingHabits(e4.getId(), FROM, TO);
        assertThat(none.views()).isZero();
        assertThat(none.avgDwellSeconds()).isNull();
        assertThat(none.completionRate()).isNull();
    }

    @Test
    void followsListNewestFirstIncludingUnfollowed() {
        List<FollowedAccount> follows = repository.follows(e2.getId());

        assertThat(follows).extracting(FollowedAccount::accountName).containsExactly("人员测试团队号", "人员测试个人号");
        assertThat(follows.get(0).unfollowTime()).isEqualTo(t(6, 9, 9));
        assertThat(follows.get(1).unfollowTime()).isNull();
    }

    @Test
    void pointsAreSummedByCategoryWithinTheRange() {
        Points points = repository.points(e2.getId(), FROM, TO);

        assertThat(points.active()).isEqualTo(15);
        assertThat(points.passive()).isEqualTo(7);
        assertThat(repository.points(e4.getId(), FROM, TO)).isEqualTo(new Points(0, 0));
    }

    @Test
    void recentEventsAreNewestFirstAndStopAtTheAsOfDay() {
        List<Event> events = repository.recentEvents(e1.getId(), TO, 3);

        assertThat(events).extracting(Event::type).containsExactly("LOGIN", "POST", "POST");
        assertThat(events.get(0).time()).isEqualTo(t(6, 6, 9));
        assertThat(repository.recentEvents(e3.getId(), TO, 5)).hasSize(1);     // 06-11 的浏览晚于统计日
    }

    // ---- 兴趣与跨领域 ----

    @Test
    void deptViewsCrossViewerDeptWithContentChannel() {
        List<DeptDomainViews> views = repository.deptDomainViews(FROM, TO).stream()
            .filter(v -> v.dept().startsWith("人员测试部")).toList();

        assertThat(views).extracting(DeptDomainViews::dept, DeptDomainViews::channel, DeptDomainViews::views).containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("人员测试部A", "人员测试领域甲", 3L),
            org.assertj.core.groups.Tuple.tuple("人员测试部A", "人员测试领域乙", 1L),
            org.assertj.core.groups.Tuple.tuple("人员测试部B", "人员测试领域甲", 1L));
    }

    @Test
    void deptReadersCountDistinctViewersAndViews() {
        List<DeptReaders> readers = repository.deptReaders(FROM, TO).stream()
            .filter(v -> v.dept().startsWith("人员测试部")).toList();

        assertThat(readers).extracting(DeptReaders::dept, DeptReaders::readers, DeptReaders::views).containsExactly(
            org.assertj.core.groups.Tuple.tuple("人员测试部A", 2L, 4L), org.assertj.core.groups.Tuple.tuple("人员测试部B", 1L, 1L));
    }
}
