package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.*;
import com.jiaxian.analytics.repository.*;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class EntityGenerator {

    /** RANDOM 员工里约 70% 在回填开始前就已经是社区成员，其余在历史期间陆续加入（月均新成员约 1.4%）。 */
    static final double EXISTING_MEMBER_RATIO = 0.70;
    static final int MAX_DAYS_BEFORE_START = 730;
    /** 新成员最晚在历史结束前这么多天加入，保证他们有时间产生行为。 */
    static final int NEWCOMER_MIN_DAYS_BEFORE_END = 14;
    /** RISING 画像的加入日：历史结束前 21–80 天，分散开，避免在同一时段集中加入。 */
    static final int RISING_JOIN_MIN_DAYS_BEFORE_END = 21;
    static final int RISING_JOIN_SPREAD_DAYS = 60;
    /** 淡出型在沉默之后有 30% 的概率回流，沉默 30–120 天后回来。 */
    static final double RETURN_RATIO = 0.30;
    static final int MIN_RETURN_GAP_DAYS = 30;
    static final int RETURN_GAP_SPREAD_DAYS = 91;
    /** 淡出起点最晚在历史结束前这么多天，保证淡出能在历史里看到。 */
    static final int FADE_MARGIN_DAYS = 30;
    /** 作者在内容发布前至少已经加入社区这么多天（先读后写）；历史开始前就加入的老成员不受限。 */
    static final int AUTHOR_WARMUP_DAYS = 7;
    /** 账号和内容归属账号的幂律指数：第 k 名账号的权重是 1/(k+1)^0.8。 */
    static final double ACCOUNT_SKEW = 0.8;
    static final double COMMON_LURKER_RATIO = 0.35;
    static final double SAME_DOMAIN_POST_RATIO = 0.7;
    private static final double DEPT_SKEW = 0.5;
    private static final double PROPENSITY_SKEW = 0.7;

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

        List<Channel> domainChannels = channelRepository.findByCategory(Channel.CATEGORY_DOMAIN);
        if (domainChannels.isEmpty()) {
            throw new IllegalStateException("No domain channels (category '" + Channel.CATEGORY_DOMAIN
                + "') found; content can only be generated into domain channels");
        }

        int employeeCount = config.getEmployeeCount();
        List<BloggerPersona> personas = config.isPersonas()
            ? PersonaAssigner.assign(employeeCount, config.getRandomSeed())
            : Collections.nCopies(employeeCount, BloggerPersona.RANDOM);
        double[] propensities = propensities(employeeCount, random);
        List<Channel> deptOrder = new ArrayList<>(domainChannels);
        Collections.shuffle(deptOrder, random);

        List<Employee> employees = new ArrayList<>();
        List<EmployeeProfile> profiles = new ArrayList<>();
        for (int i = 0; i < employeeCount; i++) {
            BloggerPersona persona = personas.get(i);
            LocalDate communityJoin = communityJoinDate(persona, config, random);
            Employee employee = employeeRepository.save(Employee.builder()
                .name("员工-" + i)
                .dept(pickDept(deptOrder, random))
                .joinDate(LocalDate.of(2018 + random.nextInt(8), 1 + random.nextInt(12), 1 + random.nextInt(28)))
                .communityJoinDate(communityJoin)
                .simulationBatchId(batchId)
                .build());
            employees.add(employee);

            LocalDate activeFrom = communityJoin.isAfter(config.getHistoryStart()) ? communityJoin : config.getHistoryStart();
            boolean bloggerPersona = persona == BloggerPersona.STAR || persona == BloggerPersona.CHURNING;
            RetentionArchetype archetype = archetype(random);
            long fadeSpan = Math.max(1, ChronoUnit.DAYS.between(activeFrom, config.getHistoryEnd()) - FADE_MARGIN_DAYS);
            LocalDate fadeStart = activeFrom.plusDays(random.nextInt((int) fadeSpan));
            int fadeLength = 30 + random.nextInt(61);
            LocalDate returnDay = archetype == RetentionArchetype.FADING && random.nextDouble() < RETURN_RATIO
                ? fadeStart.plusDays(fadeLength + MIN_RETURN_GAP_DAYS + random.nextInt(RETURN_GAP_SPREAD_DAYS)) : null;
            profiles.add(new EmployeeProfile(employee, persona, archetype,
                !bloggerPersona && random.nextDouble() < COMMON_LURKER_RATIO,
                propensities[i], fadeStart, fadeLength, returnDay));
        }
        Map<Long, Employee> employeeById = new HashMap<>();
        for (Employee employee : employees) {
            employeeById.put(employee.getId(), employee);
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

        // 账号的"体量"按幂律分布：头部账号发很多内容、粉丝多，尾部账号只发几篇；谁排头部是随机的
        Map<Long, Double> accountWeights = new HashMap<>();
        List<Account> accountRanking = new ArrayList<>(accounts);
        Collections.shuffle(accountRanking, random);
        for (int rank = 0; rank < accountRanking.size(); rank++) {
            accountWeights.put(accountRanking.get(rank).getId(), 1.0 / Math.pow(rank + 1, ACCOUNT_SKEW));
        }
        double accountWeightTotal = accountWeights.values().stream().mapToDouble(Double::doubleValue).sum();

        PopularityDistribution popularityDistribution = new PopularityDistribution(random);
        List<Double> weights = popularityDistribution.generateWeights(config.getContentCount());

        List<Content> contents = new ArrayList<>();
        for (int i = 0; i < config.getContentCount(); i++) {
            Account account = pickAccount(accounts, accountWeights, accountWeightTotal, random);

            // 先确定作者，再确定发布时间：内容不能早于任何一位作者加入社区的日期。
            List<AccountMember> members = accountMemberRepository.findByAccountId(account.getId());
            int authorCount = Math.max(1, Math.min(members.size(), 1 + random.nextInt(2)));
            List<AccountMember> authorMembers = members.subList(0, Math.min(authorCount, members.size()));
            Employee firstAuthor = authorMembers.isEmpty() ? null : employeeById.get(authorMembers.get(0).getEmployeeId());

            LocalDate earliest = config.getHistoryStart();
            for (AccountMember authorMember : authorMembers) {
                Employee author = employeeById.get(authorMember.getEmployeeId());
                if (author != null && author.getCommunityJoinDate() != null
                    && author.getCommunityJoinDate().isAfter(config.getHistoryStart())) {
                    // 历史期间才加入的作者：先读一阵子再发帖
                    LocalDate firstPostDay = author.getCommunityJoinDate().plusDays(AUTHOR_WARMUP_DAYS);
                    if (firstPostDay.isAfter(earliest)) {
                        earliest = firstPostDay;
                    }
                }
            }
            if (!earliest.isBefore(config.getHistoryEnd())) {
                earliest = config.getHistoryEnd().minusDays(1);
            }
            LocalDateTime publishTime = ActivityModel.publishTime(earliest, config.getHistoryEnd(), random);

            Channel channel = pickChannel(domainChannels, firstAuthor, random);
            Content content = contentRepository.save(Content.builder()
                .type(random.nextDouble() < 0.7 ? ContentType.ARTICLE : ContentType.VIDEO)
                .channelId(channel.getId())
                .accountId(account.getId())
                .publishTime(publishTime)
                .popularityWeight(weights.get(i))
                .simulationBatchId(batchId)
                .build());
            contents.add(content);

            for (AccountMember authorMember : authorMembers) {
                contentAuthorRepository.save(ContentAuthor.builder()
                    .contentId(content.getId())
                    .employeeId(authorMember.getEmployeeId())
                    .simulationBatchId(batchId)
                    .build());
            }
        }

        return new GeneratedEntities(employees, accounts, contents, profiles, accountWeights);
    }

    private static Account pickAccount(List<Account> accounts, Map<Long, Double> weights, double total, Random random) {
        double target = random.nextDouble() * total;
        double cumulative = 0;
        for (Account account : accounts) {
            cumulative += weights.get(account.getId());
            if (target < cumulative) {
                return account;
            }
        }
        return accounts.get(accounts.size() - 1);
    }

    /** 幂律活跃倾向：员工被随机排名，第 r 名的倾向是 1/(r+1)^0.7。 */
    private double[] propensities(int employeeCount, Random random) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < employeeCount; i++) {
            order.add(i);
        }
        Collections.shuffle(order, random);
        double[] propensity = new double[employeeCount];
        for (int rank = 0; rank < employeeCount; rank++) {
            propensity[order.get(rank)] = 1.0 / Math.pow(rank + 1, PROPENSITY_SKEW);
        }
        return propensity;
    }

    private LocalDate communityJoinDate(BloggerPersona persona, SimulationConfig config, Random random) {
        LocalDate start = config.getHistoryStart();
        LocalDate end = config.getHistoryEnd();
        if (persona == BloggerPersona.RISING) {
            return end.minusDays(RISING_JOIN_MIN_DAYS_BEFORE_END + random.nextInt(RISING_JOIN_SPREAD_DAYS));
        }
        if (persona != BloggerPersona.RANDOM || random.nextDouble() < EXISTING_MEMBER_RATIO) {
            return start.minusDays(1 + random.nextInt(MAX_DAYS_BEFORE_START));
        }
        long span = Math.max(1, ChronoUnit.DAYS.between(start, end) - NEWCOMER_MIN_DAYS_BEFORE_END);
        return start.plusDays(random.nextInt((int) span));
    }

    private RetentionArchetype archetype(Random random) {
        double roll = random.nextDouble();
        if (roll < 0.30) {
            return RetentionArchetype.REGULAR;
        }
        return roll < 0.80 ? RetentionArchetype.OCCASIONAL : RetentionArchetype.FADING;
    }

    /** 按 1/(i+1)^0.5 的权重挑一个领域作为员工的部门，让有的领域人多、有的人少。 */
    private String pickDept(List<Channel> deptOrder, Random random) {
        double total = 0;
        for (int i = 0; i < deptOrder.size(); i++) {
            total += 1.0 / Math.pow(i + 1, DEPT_SKEW);
        }
        double target = random.nextDouble() * total;
        double cumulative = 0;
        for (int i = 0; i < deptOrder.size(); i++) {
            cumulative += 1.0 / Math.pow(i + 1, DEPT_SKEW);
            if (target < cumulative) {
                return deptOrder.get(i).getName();
            }
        }
        return deptOrder.get(deptOrder.size() - 1).getName();
    }

    /** 70% 的内容发在第一作者所在领域的频道，其余随机领域。 */
    private Channel pickChannel(List<Channel> domainChannels, Employee firstAuthor, Random random) {
        if (firstAuthor != null && firstAuthor.getDept() != null && random.nextDouble() < SAME_DOMAIN_POST_RATIO) {
            for (Channel channel : domainChannels) {
                if (channel.getName().equals(firstAuthor.getDept())) {
                    return channel;
                }
            }
        }
        return domainChannels.get(random.nextInt(domainChannels.size()));
    }

    @Getter
    public static class GeneratedEntities {
        private final List<Employee> employees;
        private final List<Account> accounts;
        private final List<Content> contents;
        /** 和 employees 一一对应的行为画像，仅在模拟期间使用。 */
        private final List<EmployeeProfile> profiles;
        /** 账号 id → 体量权重（幂律），内容归属和被关注的概率都按它。 */
        private final Map<Long, Double> accountWeights;

        public GeneratedEntities(List<Employee> employees, List<Account> accounts, List<Content> contents,
                                 List<EmployeeProfile> profiles, Map<Long, Double> accountWeights) {
            this.accountWeights = accountWeights;
            this.employees = employees;
            this.accounts = accounts;
            this.contents = contents;
            this.profiles = profiles;
        }
    }
}
