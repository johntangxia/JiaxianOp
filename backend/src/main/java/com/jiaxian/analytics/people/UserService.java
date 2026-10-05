package com.jiaxian.analytics.people;

import com.jiaxian.analytics.people.PeopleQueryRepository.DeptDomainViews;
import com.jiaxian.analytics.people.PeopleQueryRepository.DeptReaders;
import com.jiaxian.analytics.people.PeopleQueryRepository.KolEntry;
import com.jiaxian.analytics.people.PeopleQueryRepository.LabelCount;
import com.jiaxian.analytics.people.PeopleQueryRepository.ReadingHabits;
import com.jiaxian.analytics.people.PeopleQueryRepository.WeekActivity;
import com.jiaxian.analytics.pv.Granularity;
import com.jiaxian.analytics.reader.ActiveDay;
import com.jiaxian.analytics.reader.Member;
import com.jiaxian.analytics.reader.ReaderQueryRepository;
import com.jiaxian.analytics.reader.ReaderQueryRepository.MemberActivity;
import com.jiaxian.analytics.reader.ReaderRole;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 站内用户分析（全员）：生命周期、价值分层、兴趣与跨领域、用户列表、用户 360。
 * 阶段和分层都在请求内现算，不缓存；窗口一律以统计日 asOf 为终点。
 */
@Service
public class UserService {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    static final int DEFAULT_INTEREST_DAYS = 90;
    static final int MIN_INTEREST_DAYS = 7;
    static final int MAX_INTEREST_DAYS = 180;
    static final int TIMELINE_WEEKS = 12;
    static final int RECENT_EVENTS = 20;
    static final Set<String> SORTS = Set.of("LAST_ACTIVE", "VIEWS", "INTERACTIONS", "POSTS", "ACTIVE_DAYS");
    private static final LocalDate LONG_AGO = LocalDate.of(1970, 1, 1);
    private static final List<String> SOURCES = List.of("ENTRY", "SEARCH", "SHARE", "OTHER");

    private final PeopleQueryRepository people;
    private final ReaderQueryRepository readers;
    private final AsOfResolver asOfResolver;

    public UserService(PeopleQueryRepository people, ReaderQueryRepository readers, AsOfResolver asOfResolver) {
        this.people = people;
        this.readers = readers;
        this.asOfResolver = asOfResolver;
    }

    /** 统计日当天全体成员的阶段、分层和近 90 天行为；上一个时点（asOf−30）的阶段只在需要时才用。 */
    private record Snapshot(LocalDate asOf, List<Member> members, Map<Long, LifecycleStage> stages,
                            Map<Long, LifecycleStage> previousStages, Map<Long, ValueScore> values,
                            Map<Long, MemberActivity> activity, Map<Long, LifetimeFacts> facts) {
    }

    private Snapshot snapshot(LocalDate asOf) {
        LocalDate previous = asOf.minusDays(LifecycleClassifier.WINDOW_DAYS);
        LocalDate windowStart = asOf.minusDays(ValueSegmenter.WINDOW_DAYS - 1);
        Map<Long, Set<LocalDate>> days = new HashMap<>();
        for (ActiveDay day : readers.activeDays(windowStart, asOf)) {
            days.computeIfAbsent(day.employeeId(), k -> new HashSet<>()).add(day.day());
        }
        Map<Long, LifetimeFacts> facts = new HashMap<>();
        people.lifetimeFacts(asOf).forEach(f -> facts.put(f.employeeId(), f));
        Map<Long, MemberActivity> activity = new HashMap<>();
        readers.memberActivity(windowStart, asOf).forEach(a -> activity.put(a.employeeId(), a));

        List<Member> members = readers.members().stream().filter(m -> m.isMemberOn(asOf)).toList();
        Map<Long, LifecycleStage> stages = new HashMap<>();
        Map<Long, LifecycleStage> previousStages = new HashMap<>();
        List<ValueInput> inputs = new ArrayList<>();
        for (Member member : members) {
            Set<LocalDate> memberDays = days.getOrDefault(member.id(), Set.of());
            LifetimeFacts f = facts.get(member.id());
            LocalDate firstActivity = f == null ? null : f.firstActivity().toLocalDate();
            stages.put(member.id(), stageAt(member, asOf, memberDays, firstActivity));
            if (member.isMemberOn(previous)) {
                previousStages.put(member.id(), stageAt(member, previous, memberDays, firstActivity));
            }
            MemberActivity a = activity.get(member.id());
            inputs.add(a == null ? new ValueInput(member.id(), null, 0, 0)
                : new ValueInput(member.id(), (int) ChronoUnit.DAYS.between(a.lastActive(), asOf), (int) a.activeDays(),
                    a.views() + 3.0 * a.interactions() + 10.0 * a.posts()));
        }
        Map<Long, ValueScore> values = new HashMap<>();
        ValueSegmenter.segment(inputs).forEach(s -> values.put(s.employeeId(), s));
        return new Snapshot(asOf, members, stages, previousStages, values, activity, facts);
    }

    private static LifecycleStage stageAt(Member member, LocalDate d, Set<LocalDate> days, LocalDate firstActivity) {
        LocalDate windowStart = d.minusDays(LifecycleClassifier.WINDOW_DAYS - 1);
        LocalDate previousStart = d.minusDays(2L * LifecycleClassifier.WINDOW_DAYS - 1);
        boolean inWindow = false;
        boolean inPrevious = false;
        for (LocalDate day : days) {
            if (day.isAfter(d)) {
                continue;
            }
            if (!day.isBefore(windowStart)) {
                inWindow = true;
            } else if (!day.isBefore(previousStart)) {
                inPrevious = true;
            }
        }
        boolean earlier = firstActivity != null && firstActivity.isBefore(previousStart);
        return LifecycleClassifier.classify(member.joinDate() == null ? LONG_AGO : member.joinDate(), d, inWindow, inPrevious, earlier);
    }

    private static Double share(long count, long total) {
        return total == 0 ? null : count / (double) total;
    }

    // ---- 生命周期 ----

    public UserLifecycleReport lifecycle(LocalDate asOfParam) {
        LocalDate asOf = asOfResolver.resolve(asOfParam);
        Snapshot snapshot = snapshot(asOf);
        Map<LifecycleStage, Long> now = count(snapshot.stages().values());
        Map<LifecycleStage, Long> before = count(snapshot.previousStages().values());
        long members = snapshot.members().size();
        List<UserLifecycleReport.StageRow> stages = new ArrayList<>();
        for (LifecycleStage stage : LifecycleStage.values()) {
            stages.add(new UserLifecycleReport.StageRow(stage, stage.label(), now.getOrDefault(stage, 0L),
                share(now.getOrDefault(stage, 0L), members), before.getOrDefault(stage, 0L)));
        }
        List<UserLifecycleReport.TransitionView> transitions = new ArrayList<>();
        for (TransitionRow row : TransitionMatrix.build(snapshot.previousStages(), snapshot.stages())) {
            List<UserLifecycleReport.Cell> cells = new ArrayList<>();
            long total = 0;
            for (LifecycleStage stage : LifecycleStage.values()) {
                long count = row.to().get(stage);
                total += count;
                cells.add(new UserLifecycleReport.Cell(stage, stage.label(), count));
            }
            transitions.add(new UserLifecycleReport.TransitionView(row.from(), row.from() == null ? "此前非成员" : row.from().label(),
                total, cells));
        }
        return new UserLifecycleReport(asOf, asOf.minusDays(LifecycleClassifier.WINDOW_DAYS), members,
            snapshot.previousStages().size(), stages, transitions);
    }

    private static Map<LifecycleStage, Long> count(Iterable<LifecycleStage> stages) {
        Map<LifecycleStage, Long> counts = new EnumMap<>(LifecycleStage.class);
        stages.forEach(s -> counts.merge(s, 1L, Long::sum));
        return counts;
    }

    // ---- 价值分层 ----

    public UserValueReport value(LocalDate asOfParam) {
        LocalDate asOf = asOfResolver.resolve(asOfParam);
        Snapshot snapshot = snapshot(asOf);
        Map<ValueSegment, long[]> counts = new EnumMap<>(ValueSegment.class);
        Map<ValueSegment, double[]> sums = new EnumMap<>(ValueSegment.class);
        for (Member member : snapshot.members()) {
            ValueSegment segment = snapshot.values().get(member.id()).segment();
            counts.computeIfAbsent(segment, k -> new long[1])[0]++;
            MemberActivity a = snapshot.activity().get(member.id());
            double[] sum = sums.computeIfAbsent(segment, k -> new double[2]);
            if (a != null) {
                sum[0] += a.views() + 3.0 * a.interactions() + 10.0 * a.posts();
                sum[1] += a.activeDays();
            }
        }
        long members = snapshot.members().size();
        List<UserValueReport.SegmentRow> rows = new ArrayList<>();
        for (ValueSegment segment : ValueSegment.values()) {
            long count = counts.containsKey(segment) ? counts.get(segment)[0] : 0;
            double[] sum = sums.getOrDefault(segment, new double[2]);
            rows.add(new UserValueReport.SegmentRow(segment, segment.label(), count, share(count, members),
                count == 0 ? null : sum[0] / count, count == 0 ? null : sum[1] / count));
        }
        return new UserValueReport(asOf, members, rows);
    }

    // ---- 兴趣与跨领域 ----

    public UserInterestReport interests(LocalDate asOfParam, Integer daysParam) {
        int days = daysParam != null ? daysParam : DEFAULT_INTEREST_DAYS;
        if (days < MIN_INTEREST_DAYS || days > MAX_INTEREST_DAYS) {
            throw new InvalidPeopleQueryException("days must be between " + MIN_INTEREST_DAYS + " and " + MAX_INTEREST_DAYS);
        }
        LocalDate asOf = asOfResolver.resolve(asOfParam);
        LocalDate from = asOf.minusDays(days - 1L);
        Map<String, Long> memberCount = new HashMap<>();
        readers.members().stream().filter(m -> m.isMemberOn(asOf) && m.dept() != null)
            .forEach(m -> memberCount.merge(m.dept(), 1L, Long::sum));
        Map<String, DeptReaders> readerCount = new HashMap<>();
        people.deptReaders(from, asOf).forEach(r -> readerCount.put(r.dept(), r));
        Map<String, List<DeptDomainViews>> byDept = new HashMap<>();
        people.deptDomainViews(from, asOf).forEach(v -> byDept.computeIfAbsent(v.dept(), k -> new ArrayList<>()).add(v));

        List<UserInterestReport.DeptInterest> result = new ArrayList<>();
        for (String dept : memberCount.keySet()) {
            DeptReaders readersOfDept = readerCount.get(dept);
            long views = readersOfDept == null ? 0 : readersOfDept.views();
            long own = byDept.getOrDefault(dept, List.of()).stream().filter(v -> v.channel().equals(dept))
                .mapToLong(DeptDomainViews::views).sum();
            List<UserInterestReport.DomainShare> others = byDept.getOrDefault(dept, List.of()).stream()
                .filter(v -> !v.channel().equals(dept))
                .sorted(Comparator.comparingLong(DeptDomainViews::views).reversed().thenComparing(DeptDomainViews::channel))
                .limit(3).map(v -> new UserInterestReport.DomainShare(v.channel(), v.views(), v.views() / (double) views)).toList();
            result.add(new UserInterestReport.DeptInterest(dept, memberCount.get(dept),
                readersOfDept == null ? 0 : readersOfDept.readers(), views, share(own, views), others));
        }
        result.sort(Comparator.comparingLong(UserInterestReport.DeptInterest::views).reversed()
            .thenComparing(UserInterestReport.DeptInterest::dept));
        return new UserInterestReport(asOf, days, result);
    }

    // ---- 用户列表 ----

    public UserListReport list(LocalDate asOfParam, String stage, String value, String dept, String q, String sort,
                               Integer limit, Integer offset) {
        LifecycleStage stageFilter = parseEnum(LifecycleStage.class, stage, "stage");
        ValueSegment valueFilter = parseEnum(ValueSegment.class, value, "value");
        String sortKey = sort == null || sort.isBlank() ? "LAST_ACTIVE" : sort.trim().toUpperCase();
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
        String nameFilter = q == null || q.isBlank() ? null : q.trim().toLowerCase();

        LocalDate asOf = asOfResolver.resolve(asOfParam);
        Snapshot snapshot = snapshot(asOf);
        List<UserListReport.UserRow> rows = new ArrayList<>();
        for (Member member : snapshot.members()) {
            LifecycleStage memberStage = snapshot.stages().get(member.id());
            ValueSegment segment = snapshot.values().get(member.id()).segment();
            if (stageFilter != null && stageFilter != memberStage || valueFilter != null && valueFilter != segment
                || deptFilter != null && !deptFilter.equals(member.dept())
                || nameFilter != null && !member.name().toLowerCase().contains(nameFilter)) {
                continue;
            }
            MemberActivity a = snapshot.activity().get(member.id());
            rows.add(new UserListReport.UserRow(member.id(), member.name(), member.dept(), member.joinDate(), memberStage,
                memberStage.label(), segment, segment.label(), a == null ? null : a.lastActive(), a == null ? 0 : a.activeDays(),
                a == null ? 0 : a.views(), a == null ? 0 : a.interactions(), a == null ? 0 : a.posts()));
        }
        rows.sort(rowComparator(sortKey));
        List<UserListReport.UserRow> page = rows.stream().skip(skip).limit(pageSize).toList();
        return new UserListReport(asOf, rows.size(), pageSize, skip, page, readers.depts());
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String text, String name) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, text.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidPeopleQueryException("Invalid value for parameter '" + name + "'");
        }
    }

    private static Comparator<UserListReport.UserRow> rowComparator(String sort) {
        Comparator<UserListReport.UserRow> byValue = switch (sort) {
            case "VIEWS" -> Comparator.comparingLong(UserListReport.UserRow::views).reversed();
            case "INTERACTIONS" -> Comparator.comparingLong(UserListReport.UserRow::interactions).reversed();
            case "POSTS" -> Comparator.comparingLong(UserListReport.UserRow::posts).reversed();
            case "ACTIVE_DAYS" -> Comparator.comparingLong(UserListReport.UserRow::activeDays).reversed();
            default -> Comparator.comparing(UserListReport.UserRow::lastActive, Comparator.nullsLast(Comparator.<LocalDate>reverseOrder()));
        };
        return byValue.thenComparingLong(UserListReport.UserRow::employeeId);
    }

    // ---- 用户 360 ----

    public UserProfile profile(long id, LocalDate asOfParam) {
        Member member = people.member(id).orElseThrow(() -> new PeopleNotFoundException(id));
        LocalDate asOf = asOfResolver.resolve(asOfParam);
        Snapshot snapshot = snapshot(asOf);

        LifecycleStage stage = snapshot.stages().get(id);
        ValueScore score = snapshot.values().get(id);
        LifetimeFacts facts = snapshot.facts().get(id);
        LocalDate kolDate = people.latestKolDate(asOf).orElse(null);
        KolEntry kolEntry = kolDate == null ? null : people.kolEntry(id, kolDate).orElse(null);
        CreatorStage creatorStage = CreatorStageClassifier.classify(facts, kolEntry != null && "STAR_KOL".equals(kolEntry.segment()));
        LifecycleStage previousStage = snapshot.previousStages().get(id);
        UserProfile.Basic basic = new UserProfile.Basic(id, member.name(), member.dept(), member.joinDate(),
            member.joinDate() == null || stage == null ? null : ChronoUnit.DAYS.between(member.joinDate(), asOf),
            stage, stage == null ? null : stage.label(), previousStage, previousStage == null ? null : previousStage.label(),
            score == null ? null : score.segment(), score == null ? null : score.segment().label(), creatorStage,
            creatorStage.label());

        MemberActivity recent = readers.memberActivity(asOf.minusDays(29), asOf).stream()
            .filter(a -> a.employeeId() == id).findFirst().orElse(null);
        ReaderRole role = recent == null ? ReaderRole.INACTIVE
            : ReaderRole.classify(recent.posts(), recent.interactions(), recent.views(), recent.events() > 0);
        UserProfile.Recent recentFigures = new UserProfile.Recent(recent == null ? 0 : recent.activeDays(),
            recent == null ? null : recent.lastActive(), recent == null ? 0 : recent.views(),
            recent == null ? 0 : recent.interactions(), recent == null ? 0 : recent.posts(), role.name(), role.label());

        LocalDate readingFrom = asOf.minusDays(ValueSegmenter.WINDOW_DAYS - 1);
        PeopleQueryRepository.Points points = people.points(id, asOf.minusDays(364), asOf);
        return new UserProfile(asOf, basic, recentFigures, timeline(id, asOf), reading(id, readingFrom, asOf),
            people.follows(id), new UserProfile.Points(points.active(), points.passive(), points.active() + points.passive()),
            people.recentEvents(id, asOf, RECENT_EVENTS), facts != null && facts.firstPost() != null,
            kolEntry == null ? null : new UserProfile.Kol(kolEntry.asOf(), kolEntry.rank(), kolEntry.segment(), segmentName(kolEntry.segment())));
    }

    private static String segmentName(String segment) {
        try {
            return com.jiaxian.analytics.kol.Segment.valueOf(segment).displayName();
        } catch (IllegalArgumentException e) {
            return segment;
        }
    }

    /** 最近 12 周（含 asOf 所在周），没有行为的周补零。 */
    private List<WeekActivity> timeline(long id, LocalDate asOf) {
        LocalDate lastWeek = Granularity.WEEK.periodStart(asOf);
        LocalDate firstWeek = lastWeek.minusWeeks(TIMELINE_WEEKS - 1L);
        Map<LocalDate, WeekActivity> byWeek = new HashMap<>();
        people.weeklyActivity(id, firstWeek, asOf).forEach(w -> byWeek.put(w.weekStart(), w));
        List<WeekActivity> weeks = new ArrayList<>();
        for (LocalDate week = firstWeek; !week.isAfter(lastWeek); week = week.plusWeeks(1)) {
            weeks.add(byWeek.getOrDefault(week, new WeekActivity(week, 0, 0, 0, 0)));
        }
        return weeks;
    }

    private UserProfile.Reading reading(long id, LocalDate from, LocalDate to) {
        ReadingHabits habits = people.readingHabits(id, from, to);
        long views = habits.views();
        List<UserProfile.Share> channels = shares(people.viewedChannels(id, from, to, 6), views);
        List<UserProfile.Share> tags = shares(people.viewedTags(id, from, to, 8), views);
        Map<String, Long> sourceCounts = new HashMap<>();
        people.viewSources(id, from, to).forEach(s -> sourceCounts.put(s.label(), s.count()));
        List<UserProfile.Share> sources = new ArrayList<>();
        for (String source : SOURCES) {
            long count = sourceCounts.getOrDefault(source, 0L);
            sources.add(new UserProfile.Share(source, count, views == 0 ? 0 : count / (double) views));
        }
        return new UserProfile.Reading(views, channels, tags, sources, share(habits.videoViews(), views),
            habits.avgDwellSeconds(), habits.completionRate());
    }

    private static List<UserProfile.Share> shares(List<LabelCount> counts, long views) {
        return counts.stream().map(c -> new UserProfile.Share(c.label(), c.count(), views == 0 ? 0 : c.count() / (double) views)).toList();
    }
}
