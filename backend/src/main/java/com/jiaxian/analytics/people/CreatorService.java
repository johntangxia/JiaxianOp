package com.jiaxian.analytics.people;

import com.jiaxian.analytics.channel.ChannelQueryRepository.ContentSide;
import com.jiaxian.analytics.drill.ExposureMetrics;
import com.jiaxian.analytics.drill.SubjectFilter;
import com.jiaxian.analytics.drill.SubjectQueryRepository;
import com.jiaxian.analytics.drill.SubjectType;
import com.jiaxian.analytics.people.PeopleQueryRepository.AccountTypeFigures;
import com.jiaxian.analytics.pv.Granularity;
import com.jiaxian.analytics.reader.ActiveDay;
import com.jiaxian.analytics.reader.CohortRow;
import com.jiaxian.analytics.reader.Member;
import com.jiaxian.analytics.reader.ReaderQueryRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 创作者分析：总览与留存、排行、成长阶梯、集中度。创作者 = 窗口内发过帖（POST 行为）的员工。 */
@Service
public class CreatorService {

    static final int MAX_DAYS = 366;
    static final int DEFAULT_PERIODS = 6;
    static final int MAX_PERIODS = 12;
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    static final Set<String> SORTS = Set.of("POSTS", "PV", "INTERACTION_RATE", "COMPLETION", "NEW_FOLLOWERS");

    private final PeopleQueryRepository people;
    private final ReaderQueryRepository readers;
    private final SubjectQueryRepository subjects;
    private final AsOfResolver asOfResolver;

    public CreatorService(PeopleQueryRepository people, ReaderQueryRepository readers, SubjectQueryRepository subjects,
                          AsOfResolver asOfResolver) {
        this.people = people;
        this.readers = readers;
        this.subjects = subjects;
        this.asOfResolver = asOfResolver;
    }

    private static void validate(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new InvalidPeopleQueryException("'to' must not be before 'from'");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new InvalidPeopleQueryException("The date range must not exceed " + MAX_DAYS + " days");
        }
    }

    // ---- 总览 ----

    public CreatorOverviewReport overview(LocalDate from, LocalDate to, Granularity granularity, Integer periods) {
        validate(from, to);
        if (granularity == Granularity.YEAR) {
            throw new InvalidPeopleQueryException("granularity must be DAY, WEEK or MONTH");
        }
        int count = periods != null ? periods : DEFAULT_PERIODS;
        if (count < 1 || count > MAX_PERIODS) {
            throw new InvalidPeopleQueryException("periods must be between 1 and " + MAX_PERIODS);
        }
        long length = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate previousTo = from.minusDays(1);
        LocalDate previousFrom = from.minusDays(length);
        LocalDate dataEnd = readers.latestActiveDay().orElse(from.minusDays(1));
        LocalDate fetchTo = dataEnd.isAfter(to) ? dataEnd : to;     // 留存要看 to 之后的发帖

        Map<Long, LocalDate> firstPost = people.firstPostDays();
        List<PostDay> postDays = people.postDays(previousFrom, fetchTo);
        long members = readers.members().stream().filter(m -> m.isMemberOn(to)).count();

        CreatorSummary summary = CreatorActivity.summary(postDays, firstPost, from, to, previousFrom, previousTo, members);
        List<CreatorPoint> series = CreatorActivity.series(postDays, firstPost, from, to, granularity);
        List<CohortRow> cohorts = CreatorRetention.build(firstPost, postDays, from, to, count, dataEnd);
        return new CreatorOverviewReport(from, to, granularity, summary, series, count, dataEnd, cohorts);
    }

    // ---- 排行 ----

    public CreatorRankingReport ranking(LocalDate from, LocalDate to, String sort, String dept, Integer limit, Integer offset) {
        validate(from, to);
        String sortKey = sort == null || sort.isBlank() ? "PV" : sort.trim().toUpperCase();
        if (!SORTS.contains(sortKey)) {
            throw new InvalidPeopleQueryException("sort must be one of " + String.join(", ", new java.util.TreeSet<>(SORTS)));
        }
        int pageSize = limit != null ? limit : DEFAULT_LIMIT;
        int skip = offset != null ? offset : 0;
        if (pageSize < 1 || pageSize > MAX_LIMIT) {
            throw new InvalidPeopleQueryException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (skip < 0) {
            throw new InvalidPeopleQueryException("offset must not be negative");
        }
        String deptFilter = dept == null || dept.isBlank() ? null : dept.trim();

        Map<Long, Long> posts = postsByCreator(from, to);
        Map<Long, Member> members = new HashMap<>();
        readers.members().forEach(m -> members.put(m.id(), m));
        Map<Long, ContentSide> sides = subjects.contentSide(SubjectType.BLOGGER, SubjectFilter.NONE, from, to);
        Map<Long, Long> interactions = subjects.interactions(SubjectType.BLOGGER, SubjectFilter.NONE, from, to);
        Map<Long, ExposureMetrics> exposure = subjects.exposure(SubjectType.BLOGGER, SubjectFilter.NONE, from, to);
        Map<Long, Long> followers = people.newFollowersByEmployee(from, to);

        List<CreatorRow> rows = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : posts.entrySet()) {
            Member member = members.get(entry.getKey());
            if (member == null || deptFilter != null && !deptFilter.equals(member.dept())) {
                continue;
            }
            ContentSide side = sides.get(member.id());
            long pv = side == null ? 0 : side.pv();
            long uv = side == null ? 0 : side.uv();
            long interaction = interactions.getOrDefault(member.id(), 0L);
            ExposureMetrics dwell = exposure.get(member.id());
            rows.add(new CreatorRow(member.id(), member.name(), member.dept(), entry.getValue(), pv, uv, interaction,
                pv == 0 ? null : interaction / (double) pv, dwell == null ? null : dwell.completionRate(),
                followers.getOrDefault(member.id(), 0L)));
        }
        rows.sort(comparator(sortKey));
        List<CreatorRow> page = rows.stream().skip(skip).limit(pageSize).toList();
        return new CreatorRankingReport(from, to, sortKey, rows.size(), pageSize, skip, page, readers.depts());
    }

    private static Comparator<CreatorRow> comparator(String sort) {
        Comparator<CreatorRow> byValue = switch (sort) {
            case "POSTS" -> Comparator.comparing((CreatorRow r) -> (double) r.posts(), Comparator.reverseOrder());
            case "INTERACTION_RATE" -> Comparator.comparing(CreatorRow::interactionRate,
                Comparator.nullsLast(Comparator.<Double>reverseOrder()));
            case "COMPLETION" -> Comparator.comparing(CreatorRow::completionRate,
                Comparator.nullsLast(Comparator.<Double>reverseOrder()));
            case "NEW_FOLLOWERS" -> Comparator.comparing((CreatorRow r) -> (double) r.newFollowers(), Comparator.reverseOrder());
            default -> Comparator.comparing((CreatorRow r) -> (double) r.pv(), Comparator.reverseOrder());
        };
        return byValue.thenComparing(Comparator.comparingLong(CreatorRow::employeeId));
    }

    private Map<Long, Long> postsByCreator(LocalDate from, LocalDate to) {
        Map<Long, Long> posts = new HashMap<>();
        people.postDays(from, to).forEach(d -> posts.merge(d.employeeId(), d.posts(), Long::sum));
        return posts;
    }

    // ---- 成长阶梯 ----

    public CreatorLadderReport ladder(LocalDate asOfParam) {
        LocalDate asOf = asOfResolver.resolve(asOfParam);
        LocalDate kolDate = people.latestKolDate(asOf).orElse(null);
        Set<Long> stars = kolDate == null ? Set.of() : people.starKols(kolDate);
        Map<Long, LifetimeFacts> facts = new HashMap<>();
        people.lifetimeFacts(asOf).forEach(f -> facts.put(f.employeeId(), f));

        Map<CreatorStage, Long> counts = new EnumMap<>(CreatorStage.class);
        long members = 0;
        long viewed = 0;
        long interacted = 0;
        long posted = 0;
        List<Long> viewDays = new ArrayList<>();
        List<Long> interactionDays = new ArrayList<>();
        List<Long> postedDays = new ArrayList<>();
        for (Member member : readers.members()) {
            if (!member.isMemberOn(asOf)) {
                continue;
            }
            members++;
            LifetimeFacts f = facts.get(member.id());
            counts.merge(CreatorStageClassifier.classify(f, stars.contains(member.id())), 1L, Long::sum);
            if (f == null) {
                continue;
            }
            if (f.firstView() != null) {
                viewed++;
                addDays(viewDays, member, f.firstView().toLocalDate());
            }
            if (f.firstInteraction() != null) {
                interacted++;
                addDays(interactionDays, member, f.firstInteraction().toLocalDate());
            }
            if (f.firstPost() != null) {
                posted++;
                addDays(postedDays, member, f.firstPost().toLocalDate());
            }
        }
        List<LadderStageCount> stages = new ArrayList<>();
        for (CreatorStage stage : CreatorStage.values()) {
            long count = counts.getOrDefault(stage, 0L);
            stages.add(new LadderStageCount(stage, stage.label(), count, members == 0 ? null : count / (double) members));
        }
        List<LadderReach> reach = List.of(
            new LadderReach("VIEW", "首次浏览", viewed, share(viewed, members), MedianCalculator.median(viewDays)),
            new LadderReach("INTERACT", "首次互动", interacted, share(interacted, members), MedianCalculator.median(interactionDays)),
            new LadderReach("POST", "首次发帖", posted, share(posted, members), MedianCalculator.median(postedDays)));
        return new CreatorLadderReport(asOf, members, kolDate, stages, reach);
    }

    private static Double share(long count, long total) {
        return total == 0 ? null : count / (double) total;
    }

    /** 从加入社区到那一步的天数；没有加入日期的旧数据没法算，跳过；早于加入日的记 0。 */
    private static void addDays(List<Long> days, Member member, LocalDate reached) {
        if (member.joinDate() != null) {
            days.add(Math.max(0, ChronoUnit.DAYS.between(member.joinDate(), reached)));
        }
    }

    // ---- 集中度 ----

    public CreatorConcentrationReport concentration(LocalDate from, LocalDate to) {
        validate(from, to);
        Map<Long, Long> posts = postsByCreator(from, to);
        Map<Long, ContentSide> sides = subjects.contentSide(SubjectType.BLOGGER, SubjectFilter.NONE, from, to);
        List<CreatorFigures> creators = new ArrayList<>();
        posts.forEach((id, count) -> {
            ContentSide side = sides.get(id);
            creators.add(new CreatorFigures(id, count, side == null ? 0 : side.pv()));
        });
        Double gini = GiniCalculator.gini(creators.stream().map(c -> (double) c.pv()).toList());
        return new CreatorConcentrationReport(from, to, creators.size(), gini, ConcentrationBuilder.build(creators),
            accountTypes(from, to));
    }

    private List<AccountTypeRow> accountTypes(LocalDate from, LocalDate to) {
        Map<Long, String> typeOfAccount = people.accountTypes();
        Map<String, long[]> pvAndInteractions = new HashMap<>();
        subjects.contentSide(SubjectType.ACCOUNT, SubjectFilter.NONE, from, to).forEach((accountId, side) ->
            pvAndInteractions.computeIfAbsent(typeOfAccount.get(accountId), k -> new long[2])[0] += side.pv());
        subjects.interactions(SubjectType.ACCOUNT, SubjectFilter.NONE, from, to).forEach((accountId, count) ->
            pvAndInteractions.computeIfAbsent(typeOfAccount.get(accountId), k -> new long[2])[1] += count);
        Map<String, AccountTypeFigures> supply = new HashMap<>();
        people.accountTypeSupply(from, to).forEach(f -> supply.put(f.type(), f));

        List<AccountTypeRow> rows = new ArrayList<>();
        for (String type : List.of("PERSONAL", "TEAM")) {
            AccountTypeFigures figures = supply.get(type);
            long[] totals = pvAndInteractions.getOrDefault(type, new long[2]);
            rows.add(new AccountTypeRow(type, "PERSONAL".equals(type) ? "个人账号" : "团队账号",
                figures == null ? 0 : figures.creators(), figures == null ? 0 : figures.posts(), totals[0], totals[1],
                totals[0] == 0 ? null : totals[1] / (double) totals[0]));
        }
        return rows;
    }
}
