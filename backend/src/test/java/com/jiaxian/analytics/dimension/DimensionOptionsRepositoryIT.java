package com.jiaxian.analytics.dimension;

import com.jiaxian.analytics.domain.Account;
import com.jiaxian.analytics.domain.AccountType;
import com.jiaxian.analytics.domain.Channel;
import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.ContentType;
import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.repository.AccountRepository;
import com.jiaxian.analytics.repository.ChannelRepository;
import com.jiaxian.analytics.repository.ContentRepository;
import com.jiaxian.analytics.repository.EmployeeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class DimensionOptionsRepositoryIT {

    @Autowired private DimensionRepository repository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private JdbcTemplate jdbc;

    private Account account(String name, AccountType type) {
        return accountRepository.save(Account.builder().name(name).type(type).simulationBatchId("dim-test").build());
    }

    private Employee employee(String name, String dept) {
        return employeeRepository.save(
            Employee.builder().name(name).dept(dept).simulationBatchId("dim-test").build());
    }

    @Test
    void channelsAreListedById() {
        Channel a = channelRepository.save(Channel.builder().name("维度测试频道甲").category("测试类").build());
        Channel b = channelRepository.save(Channel.builder().name("维度测试频道乙").category("测试类").build());

        List<ChannelOption> channels = repository.channels();

        assertThat(channels).contains(
            new ChannelOption(a.getId(), "维度测试频道甲", "测试类"),
            new ChannelOption(b.getId(), "维度测试频道乙", "测试类"));
        assertThat(channels).extracting(ChannelOption::id).isSorted();
        assertThat(channels.size()).isGreaterThanOrEqualTo(28);   // 5 个综合频道 + 23 个领域频道
    }

    @Test
    void accountsAreSearchedByKeywordIgnoringCase() {
        Account alpha = account("维度测试账号-Alpha", AccountType.PERSONAL);
        Account beta = account("维度测试账号-beta", AccountType.TEAM);
        account("别的账号", AccountType.PERSONAL);

        assertThat(repository.accounts("维度测试账号", 20)).containsExactlyInAnyOrder(
            new AccountOption(alpha.getId(), "维度测试账号-Alpha", "PERSONAL"),
            new AccountOption(beta.getId(), "维度测试账号-beta", "TEAM"));
        assertThat(repository.accounts("ALPHA", 20)).extracting(AccountOption::id).containsExactly(alpha.getId());
        assertThat(repository.accounts("  alpha ", 20)).extracting(AccountOption::id).containsExactly(alpha.getId());
        assertThat(repository.accounts("维度测试账号-不存在", 20)).isEmpty();
    }

    @Test
    void wildcardCharactersInTheKeywordAreMatchedLiterally() {
        Account literal = account("维度测试_100%账号", AccountType.PERSONAL);
        account("维度测试X100Y账号", AccountType.PERSONAL);

        assertThat(repository.accounts("_100%", 20)).extracting(AccountOption::id).containsExactly(literal.getId());
    }

    @Test
    void limitCutsTheListAndEmptyKeywordMatchesEverything() {
        account("维度测试限量-1", AccountType.PERSONAL);
        account("维度测试限量-2", AccountType.PERSONAL);
        account("维度测试限量-3", AccountType.PERSONAL);

        assertThat(repository.accounts("维度测试限量", 2)).hasSize(2);
        assertThat(repository.accounts("维度测试限量", 100)).hasSize(3);
        assertThat(repository.accounts("", 100)).hasSizeGreaterThanOrEqualTo(3);
        assertThat(repository.accounts(null, 100)).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void employeesAreSearchedByNameWithTheirDepartment() {
        Employee e = employee("维度测试员工-张三", "维度测试部");
        employee("维度测试员工-李四", null);

        assertThat(repository.employees("张三", 20)).containsExactly(
            new EmployeeOption(e.getId(), "维度测试员工-张三", "维度测试部"));
        assertThat(repository.employees("维度测试员工", 20)).hasSize(2);
    }

    @Test
    void pvRangeIsTheMinAndMaxSummaryDate() {
        Channel channel = channelRepository.save(Channel.builder().name("维度测试频道-范围").category("测试类").build());
        Account account = account("维度测试账号-范围", AccountType.PERSONAL);
        Content content = contentRepository.save(Content.builder().type(ContentType.ARTICLE)
            .channelId(channel.getId()).accountId(account.getId()).publishTime(LocalDateTime.of(2001, 1, 1, 0, 0))
            .popularityWeight(0.5).simulationBatchId("dim-test").build());
        for (LocalDate day : List.of(LocalDate.of(2001, 1, 5), LocalDate.of(2002, 2, 2))) {
            jdbc.update("INSERT INTO pv_summary_daily (content_id, summary_date, pv_count) VALUES (?, ?, 1)",
                content.getId(), Timestamp.valueOf(day.atStartOfDay()));
        }

        PvRange range = repository.pvRange();

        // 库里可能还有别的真实数据，所以只断言范围覆盖了这两天
        assertThat(range.from()).isNotNull().isBeforeOrEqualTo(LocalDate.of(2001, 1, 5));
        assertThat(range.to()).isNotNull().isAfterOrEqualTo(LocalDate.of(2002, 2, 2));
    }
}
