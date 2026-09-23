package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.*;
import com.jiaxian.analytics.repository.*;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class EntityGenerator {

    private final EmployeeRepository employeeRepository;
    private final AccountRepository accountRepository;
    private final AccountMemberRepository accountMemberRepository;
    private final ChannelRepository channelRepository;
    private final ContentRepository contentRepository;
    private final ContentAuthorRepository contentAuthorRepository;

    public EntityGenerator(EmployeeRepository employeeRepository,
                            AccountRepository accountRepository,
                            AccountMemberRepository accountMemberRepository,
                            ChannelRepository channelRepository,
                            ContentRepository contentRepository,
                            ContentAuthorRepository contentAuthorRepository) {
        this.employeeRepository = employeeRepository;
        this.accountRepository = accountRepository;
        this.accountMemberRepository = accountMemberRepository;
        this.channelRepository = channelRepository;
        this.contentRepository = contentRepository;
        this.contentAuthorRepository = contentAuthorRepository;
    }

    public GeneratedEntities generate(SimulationConfig config) {
        Random random = new Random(config.getRandomSeed());
        String batchId = config.getBatchId();

        List<Employee> employees = new ArrayList<>();
        String[] depts = {"终端", "移动无线", "固定网络", "半导体", "IT计算", "智能汽车", "知识产权", "制造", "供应链"};
        for (int i = 0; i < config.getEmployeeCount(); i++) {
            Employee employee = employeeRepository.save(Employee.builder()
                .name("员工-" + i)
                .dept(depts[random.nextInt(depts.length)])
                .joinDate(LocalDate.of(2018 + random.nextInt(8), 1 + random.nextInt(12), 1 + random.nextInt(28)))
                .simulationBatchId(batchId)
                .build());
            employees.add(employee);
        }

        List<Account> accounts = new ArrayList<>();
        int teamAccountCount = (int) Math.round(config.getAccountCount() * config.getTeamAccountRatio());
        for (int i = 0; i < config.getAccountCount(); i++) {
            boolean isTeam = i < teamAccountCount;
            Account account = accountRepository.save(Account.builder()
                .name((isTeam ? "团队账号-" : "个人账号-") + i)
                .type(isTeam ? AccountType.TEAM : AccountType.PERSONAL)
                .simulationBatchId(batchId)
                .build());
            accounts.add(account);

            int memberCount = isTeam ? 2 + random.nextInt(3) : 1;
            for (int m = 0; m < memberCount; m++) {
                Employee member = employees.get(random.nextInt(employees.size()));
                accountMemberRepository.save(AccountMember.builder()
                    .accountId(account.getId())
                    .employeeId(member.getId())
                    .simulationBatchId(batchId)
                    .build());
            }
        }

        List<Channel> channels = channelRepository.findAll();
        PopularityDistribution popularityDistribution = new PopularityDistribution(random);
        List<Double> weights = popularityDistribution.generateWeights(config.getContentCount());

        long historyDays = ChronoUnit.DAYS.between(config.getHistoryStart(), config.getHistoryEnd());
        List<Content> contents = new ArrayList<>();
        for (int i = 0; i < config.getContentCount(); i++) {
            Account account = accounts.get(random.nextInt(accounts.size()));
            Channel channel = channels.get(random.nextInt(channels.size()));
            LocalDateTime publishTime = config.getHistoryStart()
                .plusDays(random.nextInt((int) Math.max(historyDays, 1)))
                .atTime(random.nextInt(24), random.nextInt(60));

            Content content = contentRepository.save(Content.builder()
                .type(random.nextDouble() < 0.7 ? ContentType.ARTICLE : ContentType.VIDEO)
                .channelId(channel.getId())
                .accountId(account.getId())
                .publishTime(publishTime)
                .popularityWeight(weights.get(i))
                .simulationBatchId(batchId)
                .build());
            contents.add(content);

            List<AccountMember> members = accountMemberRepository.findByAccountId(account.getId());
            int authorCount = Math.max(1, Math.min(members.size(), 1 + random.nextInt(2)));
            for (int a = 0; a < authorCount && a < members.size(); a++) {
                contentAuthorRepository.save(ContentAuthor.builder()
                    .contentId(content.getId())
                    .employeeId(members.get(a).getEmployeeId())
                    .simulationBatchId(batchId)
                    .build());
            }
        }

        return new GeneratedEntities(employees, accounts, contents);
    }

    @Getter
    public static class GeneratedEntities {
        private final List<Employee> employees;
        private final List<Account> accounts;
        private final List<Content> contents;

        public GeneratedEntities(List<Employee> employees, List<Account> accounts, List<Content> contents) {
            this.employees = employees;
            this.accounts = accounts;
            this.contents = contents;
        }
    }
}
