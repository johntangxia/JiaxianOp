package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.*;
import com.jiaxian.analytics.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntityGeneratorTest {

    @Mock private EmployeeRepository employeeRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private AccountMemberRepository accountMemberRepository;
    @Mock private ChannelRepository channelRepository;
    @Mock private ContentRepository contentRepository;
    @Mock private ContentAuthorRepository contentAuthorRepository;

    @Test
    void generatesConfiguredNumberOfEmployeesAccountsAndContent() {
        when(employeeRepository.save(any())).thenAnswer(inv -> {
            Employee e = inv.getArgument(0);
            e.setId((long) (Math.random() * 1_000_000));
            return e;
        });
        when(accountRepository.save(any())).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId((long) (Math.random() * 1_000_000));
            return a;
        });
        when(contentRepository.save(any())).thenAnswer(inv -> {
            Content c = inv.getArgument(0);
            c.setId((long) (Math.random() * 1_000_000));
            return c;
        });
        List<Channel> channels = new ArrayList<>();
        for (long i = 1; i <= 15; i++) {
            channels.add(Channel.builder().id(i).name("channel-" + i).category("专业领域").build());
        }
        when(channelRepository.findAll()).thenReturn(channels);

        EntityGenerator generator = new EntityGenerator(
            employeeRepository, accountRepository, accountMemberRepository,
            channelRepository, contentRepository, contentAuthorRepository);

        SimulationConfig config = SimulationConfig.builder()
            .batchId("test-batch")
            .employeeCount(50)
            .accountCount(20)
            .teamAccountRatio(0.1)
            .contentCount(200)
            .historyStart(LocalDate.of(2025, 1, 1))
            .historyEnd(LocalDate.of(2026, 9, 23))
            .randomSeed(42)
            .build();

        EntityGenerator.GeneratedEntities result = generator.generate(config);

        assertThat(result.getEmployees()).hasSize(50);
        assertThat(result.getAccounts()).hasSize(20);
        assertThat(result.getContents()).hasSize(200);

        ArgumentCaptor<AccountMember> memberCaptor = ArgumentCaptor.forClass(AccountMember.class);
        verify(accountMemberRepository, atLeastOnce()).save(memberCaptor.capture());
        assertThat(memberCaptor.getAllValues()).allMatch(m -> "test-batch".equals(m.getSimulationBatchId()));

        long teamAccounts = result.getAccounts().stream()
            .filter(a -> a.getType() == AccountType.TEAM).count();
        assertThat(teamAccounts).isEqualTo(2);
    }
}
