package com.jiaxian.analytics.pv;

import com.jiaxian.analytics.domain.Account;
import com.jiaxian.analytics.domain.Channel;
import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.ContentType;
import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.repository.AccountRepository;
import com.jiaxian.analytics.repository.ChannelRepository;
import com.jiaxian.analytics.repository.ContentAuthorRepository;
import com.jiaxian.analytics.repository.ContentRepository;
import com.jiaxian.analytics.repository.EmployeeRepository;
import com.jiaxian.analytics.repository.PvLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PvDailyQueryRepositoryIT {

    private static final LocalDate FROM = LocalDate.of(2019, 6, 1);
    private static final LocalDate TO = LocalDate.of(2019, 6, 30);
    private static final LocalDate JUN_1 = LocalDate.of(2019, 6, 1);
    private static final LocalDate JUN_2 = LocalDate.of(2019, 6, 2);

    @Autowired private PvDailyQueryRepository repository;
    @Autowired private PvSummaryService summaryService;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private ContentAuthorRepository contentAuthorRepository;
    @Autowired private PvLogRepository pvLogRepository;

    private Channel channelA;
    private Channel channelB;
    private Account account1;
    private Account account2;
    private Employee employee1;
    private Employee employee2;
    private Employee employee3;

    @BeforeEach
    void setUp() {
        PvTestFixtures fixtures = new PvTestFixtures(channelRepository, accountRepository, employeeRepository,
            contentRepository, contentAuthorRepository, pvLogRepository);
        channelA = fixtures.channel("PV测试频道A");
        channelB = fixtures.channel("PV测试频道B");
        account1 = fixtures.account("PV测试账号1");
        account2 = fixtures.account("PV测试账号2");
        employee1 = fixtures.employee("PV测试员工1");
        employee2 = fixtures.employee("PV测试员工2");
        employee3 = fixtures.employee("PV测试员工3");
        Employee viewer = fixtures.employee("PV测试读者");

        Content c1 = fixtures.content(ContentType.ARTICLE, channelA, account1, employee1);
        Content c2 = fixtures.content(ContentType.VIDEO, channelA, account2, employee1, employee2);
        Content c3 = fixtures.content(ContentType.ARTICLE, channelB, account1, employee3, employee3);

        fixtures.viewsOnDay(c1, viewer, 2019, 6, 1, 2);
        fixtures.viewsOnDay(c1, viewer, 2019, 6, 2, 1);
        fixtures.viewsOnDay(c2, viewer, 2019, 6, 2, 4);
        fixtures.viewsOnDay(c3, viewer, 2019, 6, 1, 4);

        summaryService.rebuild(FROM, TO);
    }

    private List<DailyPv> daily(PvFilter filter) {
        return repository.dailyPv(FROM, TO, filter);
    }

    // ---- 按天 PV ----

    @Test
    void dailyPvWithoutFilterSumsAllContent() {
        assertThat(daily(PvFilter.NONE)).containsExactly(new DailyPv(JUN_1, 6), new DailyPv(JUN_2, 5));
    }

    @Test
    void dailyPvRespectsDateRangeInclusively() {
        assertThat(repository.dailyPv(JUN_2, JUN_2, PvFilter.NONE)).containsExactly(new DailyPv(JUN_2, 5));
    }

    @Test
    void dailyPvFiltersByChannel() {
        assertThat(daily(new PvFilter(channelA.getId(), null, null, null)))
            .containsExactly(new DailyPv(JUN_1, 2), new DailyPv(JUN_2, 5));
    }

    @Test
    void dailyPvFiltersByAccount() {
        assertThat(daily(new PvFilter(null, account1.getId(), null, null)))
            .containsExactly(new DailyPv(JUN_1, 6), new DailyPv(JUN_2, 1));
    }

    @Test
    void dailyPvFiltersByContentType() {
        assertThat(daily(new PvFilter(null, null, null, ContentType.VIDEO)))
            .containsExactly(new DailyPv(JUN_2, 4));
    }

    @Test
    void dailyPvFiltersByCoAuthoringEmployee() {
        assertThat(daily(new PvFilter(null, null, employee1.getId(), null)))
            .containsExactly(new DailyPv(JUN_1, 2), new DailyPv(JUN_2, 5));
        // 员工2只是 c2 的共同作者，同样拿到 c2 的完整 PV
        assertThat(daily(new PvFilter(null, null, employee2.getId(), null)))
            .containsExactly(new DailyPv(JUN_2, 4));
    }

    @Test
    void duplicateContentAuthorRowsDoNotDoubleCount() {
        assertThat(daily(new PvFilter(null, null, employee3.getId(), null)))
            .containsExactly(new DailyPv(JUN_1, 4));
    }

    @Test
    void filtersAreCombinedWithAnd() {
        assertThat(daily(new PvFilter(channelA.getId(), null, null, ContentType.VIDEO)))
            .containsExactly(new DailyPv(JUN_2, 4));
        assertThat(daily(new PvFilter(channelA.getId(), null, employee2.getId(), null)))
            .containsExactly(new DailyPv(JUN_2, 4));
        assertThat(daily(new PvFilter(channelB.getId(), null, null, ContentType.VIDEO))).isEmpty();
    }

    @Test
    void unknownFilterIdYieldsNoRows() {
        assertThat(daily(new PvFilter(-1L, null, null, null))).isEmpty();
    }

    // ---- Top N ----

    @Test
    void topChannels() {
        assertThat(repository.top(PvDimension.CHANNEL, FROM, TO, PvFilter.NONE, 10)).containsExactly(
            new PvTopEntry(channelA.getId(), "PV测试频道A", 7),
            new PvTopEntry(channelB.getId(), "PV测试频道B", 4));
    }

    @Test
    void topAccounts() {
        assertThat(repository.top(PvDimension.ACCOUNT, FROM, TO, PvFilter.NONE, 10)).containsExactly(
            new PvTopEntry(account1.getId(), "PV测试账号1", 7),
            new PvTopEntry(account2.getId(), "PV测试账号2", 4));
    }

    @Test
    void topEmployeesGiveFullCreditToCoAuthorsAndBreakTiesById() {
        assertThat(repository.top(PvDimension.EMPLOYEE, FROM, TO, PvFilter.NONE, 10)).containsExactly(
            new PvTopEntry(employee1.getId(), "PV测试员工1", 7),
            new PvTopEntry(employee2.getId(), "PV测试员工2", 4),   // 与员工3并列 4，id 小的在前
            new PvTopEntry(employee3.getId(), "PV测试员工3", 4));  // 重复作者行没有让它变成 8
    }

    @Test
    void topContentTypesHaveNullId() {
        assertThat(repository.top(PvDimension.CONTENT_TYPE, FROM, TO, PvFilter.NONE, 10)).containsExactly(
            new PvTopEntry(null, "ARTICLE", 7),
            new PvTopEntry(null, "VIDEO", 4));
    }

    @Test
    void topRespectsLimitFilterAndDateRange() {
        assertThat(repository.top(PvDimension.CHANNEL, FROM, TO, PvFilter.NONE, 1))
            .containsExactly(new PvTopEntry(channelA.getId(), "PV测试频道A", 7));

        assertThat(repository.top(PvDimension.ACCOUNT, FROM, TO, new PvFilter(channelA.getId(), null, null, null), 10))
            .containsExactly(
                new PvTopEntry(account2.getId(), "PV测试账号2", 4),
                new PvTopEntry(account1.getId(), "PV测试账号1", 3));

        // 只看 06-02：频道A = c1(1) + c2(4) = 5，频道B 没有数据
        assertThat(repository.top(PvDimension.CHANNEL, JUN_2, JUN_2, PvFilter.NONE, 10))
            .containsExactly(new PvTopEntry(channelA.getId(), "PV测试频道A", 5));
    }

    @Test
    void topRejectsLimitOutsideSafeRange() {
        // @Repository 的异常转换会把 IllegalArgumentException 包成 InvalidDataAccessApiUsageException
        assertThatThrownBy(() -> repository.top(PvDimension.CHANNEL, FROM, TO, PvFilter.NONE, 0))
            .isInstanceOf(InvalidDataAccessApiUsageException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> repository.top(PvDimension.CHANNEL, FROM, TO, PvFilter.NONE, 101))
            .isInstanceOf(InvalidDataAccessApiUsageException.class).hasMessageContaining("limit");
    }
}
