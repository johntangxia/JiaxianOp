package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class FactAndPointsRepositoryIT {

    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private PvLogRepository pvLogRepository;
    @Autowired private EngagementLogRepository engagementLogRepository;
    @Autowired private ActiveActionLogRepository activeActionLogRepository;
    @Autowired private EditorialPickLogRepository editorialPickLogRepository;
    @Autowired private PointsWeightConfigRepository pointsWeightConfigRepository;
    @Autowired private PointsLedgerRepository pointsLedgerRepository;

    @Test
    void savesAndQueriesFactAndPointsRecords() {
        Employee viewer = employeeRepository.save(
            Employee.builder().name("王五").simulationBatchId("test-batch").build());
        Employee author = employeeRepository.save(
            Employee.builder().name("赵六").simulationBatchId("test-batch").build());
        Channel channel = channelRepository.save(
            Channel.builder().name("移动无线").category("专业领域").build());
        Account account = accountRepository.save(
            Account.builder().name("赵六的账号").type(AccountType.PERSONAL)
                .simulationBatchId("test-batch").build());
        Content content = contentRepository.save(
            Content.builder().type(ContentType.ARTICLE).channelId(channel.getId())
                .accountId(account.getId()).publishTime(LocalDateTime.now())
                .popularityWeight(0.5).simulationBatchId("test-batch").build());

        pvLogRepository.save(PvLog.builder().contentId(content.getId())
            .viewerEmployeeId(viewer.getId()).viewTime(LocalDateTime.now())
            .simulationBatchId("test-batch").build());

        engagementLogRepository.save(EngagementLog.builder().contentId(content.getId())
            .actorEmployeeId(viewer.getId()).actionType(EngagementActionType.LIKE)
            .occurTime(LocalDateTime.now()).simulationBatchId("test-batch").build());

        activeActionLogRepository.save(ActiveActionLog.builder().employeeId(viewer.getId())
            .actionType(ActiveActionType.LIKE).refId(content.getId())
            .occurTime(LocalDateTime.now()).simulationBatchId("test-batch").build());

        editorialPickLogRepository.save(EditorialPickLog.builder().contentId(content.getId())
            .pickTime(LocalDateTime.now()).simulationBatchId("test-batch").build());

        pointsWeightConfigRepository.save(PointsWeightConfig.builder()
            .actionType("LIKE").category(PointsCategory.PASSIVE).weight(3)
            .effectiveDate(LocalDate.of(2026, 1, 1)).build());

        pointsLedgerRepository.save(PointsLedger.builder().employeeId(author.getId())
            .actionType("LIKE").category(PointsCategory.PASSIVE).points(3)
            .refId(content.getId()).occurTime(LocalDateTime.now())
            .simulationBatchId("test-batch").build());

        assertThat(pvLogRepository.findByContentId(content.getId())).hasSize(1);
        assertThat(engagementLogRepository.findByContentId(content.getId())).hasSize(1);
        List<PointsLedger> ledger = pointsLedgerRepository.findByEmployeeId(author.getId());
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).getPoints()).isEqualTo(3);
    }
}
