package com.jiaxian.analytics.pv;

import com.jiaxian.analytics.domain.Account;
import com.jiaxian.analytics.domain.AccountType;
import com.jiaxian.analytics.domain.Channel;
import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.ContentAuthor;
import com.jiaxian.analytics.domain.ContentType;
import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.domain.PvLog;
import com.jiaxian.analytics.repository.AccountRepository;
import com.jiaxian.analytics.repository.ChannelRepository;
import com.jiaxian.analytics.repository.ContentAuthorRepository;
import com.jiaxian.analytics.repository.ContentRepository;
import com.jiaxian.analytics.repository.EmployeeRepository;
import com.jiaxian.analytics.repository.PvLogRepository;

import java.time.LocalDateTime;

/** 集成测试用的夹具构造器。名字统一带"PV测试"前缀，避免和种子数据（频道、权重）重名。 */
class PvTestFixtures {

    private static final String BATCH = "pv-test";

    private final ChannelRepository channelRepository;
    private final AccountRepository accountRepository;
    private final EmployeeRepository employeeRepository;
    private final ContentRepository contentRepository;
    private final ContentAuthorRepository contentAuthorRepository;
    private final PvLogRepository pvLogRepository;

    PvTestFixtures(ChannelRepository channelRepository, AccountRepository accountRepository,
                   EmployeeRepository employeeRepository, ContentRepository contentRepository,
                   ContentAuthorRepository contentAuthorRepository, PvLogRepository pvLogRepository) {
        this.channelRepository = channelRepository;
        this.accountRepository = accountRepository;
        this.employeeRepository = employeeRepository;
        this.contentRepository = contentRepository;
        this.contentAuthorRepository = contentAuthorRepository;
        this.pvLogRepository = pvLogRepository;
    }

    Channel channel(String name) {
        return channelRepository.save(Channel.builder().name(name).category("专业领域").build());
    }

    Account account(String name) {
        return accountRepository.save(
            Account.builder().name(name).type(AccountType.PERSONAL).simulationBatchId(BATCH).build());
    }

    Employee employee(String name) {
        return employeeRepository.save(Employee.builder().name(name).simulationBatchId(BATCH).build());
    }

    /** 创建内容并登记作者（authors 里同一个人出现两次就会产生重复的 content_author 行）。 */
    Content content(ContentType type, Channel channel, Account account, Employee... authors) {
        Content content = contentRepository.save(Content.builder()
            .type(type).channelId(channel.getId()).accountId(account.getId())
            .publishTime(LocalDateTime.of(2019, 1, 1, 0, 0))
            .popularityWeight(0.5).simulationBatchId(BATCH).build());
        for (Employee author : authors) {
            contentAuthorRepository.save(ContentAuthor.builder()
                .contentId(content.getId()).employeeId(author.getId()).simulationBatchId(BATCH).build());
        }
        return content;
    }

    void views(Content content, Employee viewer, LocalDateTime... viewTimes) {
        for (LocalDateTime viewTime : viewTimes) {
            pvLogRepository.save(PvLog.builder()
                .contentId(content.getId()).viewerEmployeeId(viewer.getId())
                .viewTime(viewTime).simulationBatchId(BATCH).build());
        }
    }

    /** 同一天内重复 count 次浏览。 */
    void viewsOnDay(Content content, Employee viewer, int year, int month, int day, int count) {
        LocalDateTime[] times = new LocalDateTime[count];
        for (int i = 0; i < count; i++) {
            times[i] = LocalDateTime.of(year, month, day, 9, i % 60);
        }
        views(content, viewer, times);
    }
}
