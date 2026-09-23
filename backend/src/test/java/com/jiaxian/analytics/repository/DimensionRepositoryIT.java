package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class DimensionRepositoryIT {

    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountMemberRepository accountMemberRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private ContentAuthorRepository contentAuthorRepository;
    @Autowired private CommentRepository commentRepository;

    @Test
    void savesAndLoadsFullDimensionGraph() {
        Employee author1 = employeeRepository.save(
            Employee.builder().name("张三").dept("终端").joinDate(LocalDate.of(2020, 1, 1))
                .simulationBatchId("test-batch").build());
        Employee author2 = employeeRepository.save(
            Employee.builder().name("李四").dept("半导体").joinDate(LocalDate.of(2021, 3, 1))
                .simulationBatchId("test-batch").build());

        Channel channel = channelRepository.save(
            Channel.builder().name("测试频道-维度测试").category("专业领域").build());

        Account teamAccount = accountRepository.save(
            Account.builder().name("芯观察").type(AccountType.TEAM).simulationBatchId("test-batch").build());

        accountMemberRepository.save(
            AccountMember.builder().accountId(teamAccount.getId()).employeeId(author1.getId())
                .simulationBatchId("test-batch").build());
        accountMemberRepository.save(
            AccountMember.builder().accountId(teamAccount.getId()).employeeId(author2.getId())
                .simulationBatchId("test-batch").build());

        Content content = contentRepository.save(
            Content.builder().type(ContentType.ARTICLE).channelId(channel.getId())
                .accountId(teamAccount.getId()).publishTime(LocalDateTime.now())
                .popularityWeight(0.8).simulationBatchId("test-batch").build());

        contentAuthorRepository.save(
            ContentAuthor.builder().contentId(content.getId()).employeeId(author1.getId())
                .simulationBatchId("test-batch").build());
        contentAuthorRepository.save(
            ContentAuthor.builder().contentId(content.getId()).employeeId(author2.getId())
                .simulationBatchId("test-batch").build());

        Comment rootComment = commentRepository.save(
            Comment.builder().contentId(content.getId()).employeeId(author2.getId())
                .createTime(LocalDateTime.now()).simulationBatchId("test-batch").build());
        Comment reply = commentRepository.save(
            Comment.builder().contentId(content.getId()).employeeId(author1.getId())
                .parentCommentId(rootComment.getId()).createTime(LocalDateTime.now())
                .simulationBatchId("test-batch").build());

        assertThat(contentAuthorRepository.findByContentId(content.getId())).hasSize(2);
        assertThat(accountMemberRepository.findByAccountId(teamAccount.getId())).hasSize(2);
        assertThat(commentRepository.findByParentCommentId(rootComment.getId())).containsExactly(reply);
    }
}
