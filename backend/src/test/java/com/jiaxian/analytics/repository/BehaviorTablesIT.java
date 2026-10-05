package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class BehaviorTablesIT {

    private static final String BATCH = "behavior-tables-it";
    private static final LocalDateTime T = LocalDateTime.of(2019, 6, 3, 10, 0);

    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private PvLogRepository pvLogRepository;
    @Autowired private ImpressionRepository impressionRepository;
    @Autowired private FollowRelationRepository followRelationRepository;
    @Autowired private SearchLogRepository searchLogRepository;
    @Autowired private ContentTagRepository contentTagRepository;

    private Employee employee;
    private Account account;
    private Channel channel;
    private Content content;

    @BeforeEach
    void fixtures() {
        employee = employeeRepository.save(Employee.builder().name("行为表测试员工").simulationBatchId(BATCH).build());
        account = accountRepository.save(Account.builder().name("行为表测试账号").type(AccountType.PERSONAL)
            .simulationBatchId(BATCH).build());
        channel = channelRepository.save(Channel.builder().name("行为表测试频道").category("测试类").build());
        content = contentRepository.save(Content.builder().type(ContentType.ARTICLE).channelId(channel.getId())
            .accountId(account.getId()).publishTime(T).popularityWeight(0.5).simulationBatchId(BATCH).build());
    }

    @Test
    void pvLogKeepsSourceEntryAndDwell() {
        PvLog saved = pvLogRepository.saveAndFlush(PvLog.builder().contentId(content.getId())
            .viewerEmployeeId(employee.getId()).viewTime(T).entryChannelId(channel.getId())
            .sourceType(ViewSource.ENTRY).dwellSeconds(75).completed(true).simulationBatchId(BATCH).build());

        PvLog loaded = pvLogRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getSourceType()).isEqualTo(ViewSource.ENTRY);
        assertThat(loaded.getEntryChannelId()).isEqualTo(channel.getId());
        assertThat(loaded.getDwellSeconds()).isEqualTo(75);
        assertThat(loaded.getCompleted()).isTrue();
    }

    @Test
    void pvLogWithoutSourceStillWorksForLegacyData() {
        PvLog saved = pvLogRepository.saveAndFlush(PvLog.builder().contentId(content.getId())
            .viewerEmployeeId(employee.getId()).viewTime(T).simulationBatchId(BATCH).build());

        PvLog loaded = pvLogRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getSourceType()).isNull();
        assertThat(loaded.getDwellSeconds()).isNull();
    }

    @Test
    void impressionsFollowsSearchesAndTagsRoundTrip() {
        impressionRepository.saveAndFlush(Impression.builder().viewerEmployeeId(employee.getId())
            .contentId(content.getId()).entryChannelId(channel.getId()).impressionTime(T).clicked(false)
            .simulationBatchId(BATCH).build());
        FollowRelation follow = followRelationRepository.saveAndFlush(FollowRelation.builder()
            .followerEmployeeId(employee.getId()).accountId(account.getId()).followTime(T)
            .unfollowTime(T.plusDays(10)).simulationBatchId(BATCH).build());
        searchLogRepository.saveAndFlush(SearchLog.builder().employeeId(employee.getId()).keyword("半导体")
            .searchTime(T).simulationBatchId(BATCH).build());
        contentTagRepository.saveAndFlush(ContentTag.builder().contentId(content.getId()).tag("半导体")
            .simulationBatchId(BATCH).build());

        assertThat(impressionRepository.findAll()).anyMatch(i -> !i.isClicked() && i.getContentId().equals(content.getId()));
        assertThat(followRelationRepository.findByAccountId(account.getId())).hasSize(1);
        assertThat(follow.activeAt(T.plusDays(5))).isTrue();
        assertThat(follow.activeAt(T.minusSeconds(1))).isFalse();
        assertThat(follow.activeAt(T.plusDays(10))).isFalse();
        assertThat(searchLogRepository.findAll()).anyMatch(s -> "半导体".equals(s.getKeyword()) && s.getClickedContentId() == null);
        assertThat(contentTagRepository.findByContentId(content.getId())).extracting(ContentTag::getTag).containsExactly("半导体");
    }

    @Test
    void aTagCannotBeAddedTwiceToTheSameContent() {
        contentTagRepository.saveAndFlush(ContentTag.builder().contentId(content.getId()).tag("重复").build());

        assertThatThrownBy(() -> contentTagRepository.saveAndFlush(
            ContentTag.builder().contentId(content.getId()).tag("重复").build()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
