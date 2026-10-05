package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.*;
import com.jiaxian.analytics.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EntityGeneratorTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 3, 1);

    @Mock private EmployeeRepository employeeRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private AccountMemberRepository accountMemberRepository;
    @Mock private ChannelRepository channelRepository;
    @Mock private ContentRepository contentRepository;
    @Mock private ContentAuthorRepository contentAuthorRepository;

    private final AtomicLong ids = new AtomicLong(1);
    private final List<AccountMember> members = new ArrayList<>();
    private final List<ContentAuthor> contentAuthors = new ArrayList<>();
    private List<Channel> domainChannels;

    @BeforeEach
    void wireInMemoryRepositories() {
        when(employeeRepository.save(any())).thenAnswer(inv -> {
            Employee e = inv.getArgument(0);
            e.setId(ids.getAndIncrement());
            return e;
        });
        when(accountRepository.save(any())).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(ids.getAndIncrement());
            return a;
        });
        when(accountMemberRepository.save(any())).thenAnswer(inv -> {
            AccountMember m = inv.getArgument(0);
            members.add(m);
            return m;
        });
        when(accountMemberRepository.findByAccountId(anyLong())).thenAnswer(inv ->
            members.stream().filter(m -> m.getAccountId().equals(inv.getArgument(0))).toList());
        when(contentRepository.save(any())).thenAnswer(inv -> {
            Content c = inv.getArgument(0);
            c.setId(ids.getAndIncrement());
            return c;
        });
        when(contentAuthorRepository.save(any())).thenAnswer(inv -> {
            ContentAuthor a = inv.getArgument(0);
            contentAuthors.add(a);
            return a;
        });
        domainChannels = new ArrayList<>();
        for (long i = 1; i <= 23; i++) {
            domainChannels.add(Channel.builder().id(i).name("领域-" + i).category(Channel.CATEGORY_DOMAIN).build());
        }
        when(channelRepository.findByCategory(Channel.CATEGORY_DOMAIN)).thenReturn(domainChannels);
    }

    private EntityGenerator generator() {
        return new EntityGenerator(employeeRepository, accountRepository, accountMemberRepository,
            channelRepository, contentRepository, contentAuthorRepository);
    }

    private SimulationConfig.SimulationConfigBuilder config() {
        return SimulationConfig.builder().batchId("test-batch").employeeCount(200).accountCount(40)
            .teamAccountRatio(0.25).contentCount(400).historyStart(START).historyEnd(END).randomSeed(42);
    }

    @Test
    void generatesConfiguredNumberOfEmployeesAccountsAndContent() {
        EntityGenerator.GeneratedEntities result = generator().generate(config()
            .employeeCount(50).accountCount(20).teamAccountRatio(0.1).contentCount(200).build());

        assertThat(result.getEmployees()).hasSize(50);
        assertThat(result.getProfiles()).hasSize(50);
        assertThat(result.getAccounts()).hasSize(20);
        assertThat(result.getContents()).hasSize(200);
        assertThat(members).allMatch(m -> "test-batch".equals(m.getSimulationBatchId()));
        assertThat(result.getAccounts().stream().filter(a -> a.getType() == AccountType.TEAM).count()).isEqualTo(2);
    }

    @Test
    void departmentsAreDomainChannelNamesAndSpreadUnevenly() {
        EntityGenerator.GeneratedEntities result = generator().generate(config().build());

        List<String> names = domainChannels.stream().map(Channel::getName).toList();
        assertThat(result.getEmployees()).extracting(Employee::getDept).allMatch(names::contains);
        Map<String, Long> perDept = new HashMap<>();
        result.getEmployees().forEach(e -> perDept.merge(e.getDept(), 1L, Long::sum));
        assertThat(perDept.size()).isGreaterThan(10);
        assertThat(perDept.values().stream().mapToLong(Long::longValue).max().orElse(0))
            .isGreaterThan(perDept.values().stream().mapToLong(Long::longValue).min().orElse(0) * 2);
    }

    @Test
    void aboutFortyFivePercentAreExistingMembersAndNewcomersJoinDuringHistory() {
        EntityGenerator.GeneratedEntities result = generator().generate(config().build());

        long existing = result.getEmployees().stream().filter(e -> e.getCommunityJoinDate().isBefore(START)).count();
        assertThat(existing / 200.0).isBetween(0.35, 0.55);
        assertThat(result.getEmployees()).allMatch(e -> e.getCommunityJoinDate().isBefore(END.minusDays(13)));
        assertThat(result.getEmployees().stream().filter(e -> !e.getCommunityJoinDate().isBefore(START)).count())
            .isGreaterThan(50);
    }

    @Test
    void personasDetermineHowLongAgoSomeoneJoined() {
        EntityGenerator.GeneratedEntities result = generator().generate(config().personas(true).build());

        for (EmployeeProfile profile : result.getProfiles()) {
            LocalDate join = profile.joinDate();
            switch (profile.persona()) {
                case RISING -> {
                    long daysBeforeEnd = java.time.temporal.ChronoUnit.DAYS.between(join, END);
                    assertThat(daysBeforeEnd).isBetween(21L, 25L);
                }
                case STAR, CHURNING, DORMANT -> assertThat(join).isBefore(START);
                case RANDOM -> { }
            }
        }
        assertThat(result.getProfiles()).filteredOn(p -> p.persona() == BloggerPersona.STAR).isNotEmpty()
            .noneMatch(EmployeeProfile::lurker);
    }

    @Test
    void aboutAThirdAreLurkersAndTheyAreNotAllLowPropensity() {
        EntityGenerator.GeneratedEntities result = generator().generate(config().build());

        long lurkers = result.getProfiles().stream().filter(EmployeeProfile::lurker).count();
        assertThat(lurkers / 200.0).isBetween(0.25, 0.45);
        assertThat(result.getProfiles()).extracting(EmployeeProfile::propensity)
            .allMatch(p -> p > 0 && p <= 1.0);
        assertThat(result.getProfiles().stream().map(EmployeeProfile::archetype).distinct().count()).isEqualTo(3);
    }

    @Test
    void contentIsPublishedAfterEveryAuthorJoinedAndOnlyInDomainChannels() {
        EntityGenerator.GeneratedEntities result = generator().generate(config().build());

        Map<Long, Employee> employeeById = new HashMap<>();
        result.getEmployees().forEach(e -> employeeById.put(e.getId(), e));
        Map<Long, Content> contentById = new HashMap<>();
        result.getContents().forEach(c -> contentById.put(c.getId(), c));

        assertThat(contentAuthors).isNotEmpty();
        for (ContentAuthor author : contentAuthors) {
            LocalDate join = employeeById.get(author.getEmployeeId()).getCommunityJoinDate();
            LocalDate published = contentById.get(author.getContentId()).getPublishTime().toLocalDate();
            assertThat(published).as("content %d by employee %d", author.getContentId(), author.getEmployeeId())
                .isAfterOrEqualTo(join);
        }
        List<Long> domainIds = domainChannels.stream().map(Channel::getId).toList();
        assertThat(result.getContents()).extracting(Content::getChannelId).allMatch(domainIds::contains);
        assertThat(result.getContents()).extracting(c -> c.getPublishTime().toLocalDate())
            .allMatch(d -> !d.isBefore(START) && d.isBefore(END));
    }

    @Test
    void mostContentIsPostedInTheFirstAuthorsOwnDomain() {
        EntityGenerator.GeneratedEntities result = generator().generate(config().build());

        Map<Long, Employee> employeeById = new HashMap<>();
        result.getEmployees().forEach(e -> employeeById.put(e.getId(), e));
        Map<Long, String> channelName = new HashMap<>();
        domainChannels.forEach(c -> channelName.put(c.getId(), c.getName()));
        Map<Long, Long> firstAuthorByContent = new HashMap<>();
        contentAuthors.forEach(a -> firstAuthorByContent.putIfAbsent(a.getContentId(), a.getEmployeeId()));

        long sameDomain = result.getContents().stream().filter(c -> channelName.get(c.getChannelId())
            .equals(employeeById.get(firstAuthorByContent.get(c.getId())).getDept())).count();
        assertThat(sameDomain / (double) result.getContents().size()).isBetween(0.65, 0.90);
    }

    @Test
    void failsClearlyWhenThereAreNoDomainChannels() {
        when(channelRepository.findByCategory(anyString())).thenReturn(List.of());

        assertThatThrownBy(() -> generator().generate(config().build()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("domain channels");
    }
}
