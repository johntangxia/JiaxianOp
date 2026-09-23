package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.Account;
import com.jiaxian.analytics.domain.AccountType;
import com.jiaxian.analytics.domain.Channel;
import com.jiaxian.analytics.domain.Comment;
import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.ContentType;
import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.domain.EngagementActionType;
import com.jiaxian.analytics.domain.EngagementLog;
import com.jiaxian.analytics.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class HistoricalBackfillServiceIT {

    @Autowired private HistoricalBackfillService backfillService;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private PvLogRepository pvLogRepository;
    @Autowired private PointsLedgerRepository pointsLedgerRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CommentRepository commentRepository;
    @Autowired private EngagementLogRepository engagementLogRepository;

    @Test
    void runBackfillPopulatesDataAndRerunClearsPreviousBatch() {
        SimulationConfig firstRun = SimulationConfig.builder()
            .batchId("batch-1")
            .employeeCount(20)
            .accountCount(10)
            .teamAccountRatio(0.2)
            .contentCount(30)
            .historyStart(LocalDate.of(2026, 1, 1))
            .historyEnd(LocalDate.of(2026, 3, 1))
            .randomSeed(1)
            .build();

        backfillService.runBackfill(firstRun);

        assertThat(employeeRepository.count()).isEqualTo(20);
        assertThat(contentRepository.count()).isEqualTo(30);
        assertThat(pvLogRepository.count()).isGreaterThan(0);
        assertThat(pointsLedgerRepository.count()).isGreaterThan(0);

        SimulationConfig secondRun = SimulationConfig.builder()
            .batchId("batch-2")
            .employeeCount(15)
            .accountCount(8)
            .teamAccountRatio(0.2)
            .contentCount(20)
            .historyStart(LocalDate.of(2026, 1, 1))
            .historyEnd(LocalDate.of(2026, 3, 1))
            .randomSeed(2)
            .build();

        backfillService.runBackfill(secondRun);

        assertThat(employeeRepository.count()).isEqualTo(15);
        assertThat(contentRepository.count()).isEqualTo(20);
        assertThat(employeeRepository.findAll()).allMatch(e -> "batch-2".equals(e.getSimulationBatchId()));
    }

    // deleteReplies() 是一条 @Modifying 批量 JPQL DELETE,需要一个由 Spring 管理的事务才能对它
    // 执行 flush()。生产代码路径里这一切都发生在 HistoricalBackfillService.runBackfill() 自身的
    // @Transactional 方法内部;这里直接调用各个 repository,所以显式声明 @Transactional 来复刻同样
    // 的事务边界(而不是改变要验证的落库顺序——外键检查是逐语句生效的,不受事务边界影响)。
    @Test
    @Transactional
    void deletingReplyCommentsAfterEngagementLogClearedDoesNotViolateForeignKey() {
        Employee author = employeeRepository.save(
            Employee.builder().name("FK测试作者").simulationBatchId("fk-regression-test").build());
        Channel channel = channelRepository.findAll().get(0);
        Account account = accountRepository.save(Account.builder().name("FK测试账号")
            .type(AccountType.PERSONAL).simulationBatchId("fk-regression-test").build());
        Content content = contentRepository.save(Content.builder().type(ContentType.ARTICLE)
            .channelId(channel.getId()).accountId(account.getId()).publishTime(LocalDateTime.now())
            .popularityWeight(0.5).simulationBatchId("fk-regression-test").build());

        Comment topLevel = commentRepository.save(Comment.builder().contentId(content.getId())
            .employeeId(author.getId()).createTime(LocalDateTime.now())
            .simulationBatchId("fk-regression-test").build());
        Comment reply = commentRepository.save(Comment.builder().contentId(content.getId())
            .employeeId(author.getId()).parentCommentId(topLevel.getId())
            .createTime(LocalDateTime.now()).simulationBatchId("fk-regression-test").build());

        engagementLogRepository.save(EngagementLog.builder().commentId(reply.getId())
            .actorEmployeeId(author.getId()).actionType(EngagementActionType.LIKE)
            .occurTime(LocalDateTime.now()).simulationBatchId("fk-regression-test").build());

        // 复刻 clearPreviousSimulationData() 里真实的调用顺序:先删 engagement_log(逐条实体删除,
        // 删除动作先排进持久化上下文),再删 comment 里的回复(批量 JPQL delete)。如果
        // CommentRepository.deleteReplies() 的 flushAutomatically/clearAutomatically 被意外去掉,
        // 这里会在批量 delete 时报 engagement_log_comment_id_fkey 外键冲突。
        engagementLogRepository.deleteAll();
        commentRepository.deleteReplies();
        commentRepository.deleteAll();

        assertThat(commentRepository.findById(topLevel.getId())).isEmpty();
        assertThat(commentRepository.findById(reply.getId())).isEmpty();
    }
}
