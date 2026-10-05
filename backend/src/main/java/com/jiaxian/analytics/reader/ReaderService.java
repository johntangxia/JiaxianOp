package com.jiaxian.analytics.reader;

import com.jiaxian.analytics.pv.Granularity;
import com.jiaxian.analytics.reader.ReaderQueryRepository.LurkerPage;
import com.jiaxian.analytics.reader.ReaderQueryRepository.MemberActivity;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 读者侧分析：活跃与增长、新成员留存同期群、激活漏斗、读者分层和沉默读者名单。 */
@Service
public class ReaderService {

    static final int MAX_DAYS = 366;
    static final int WEEKLY_PERIODS = 8;
    static final int MONTHLY_PERIODS = 6;
    static final int MAX_PERIODS = 12;
    static final int DEFAULT_LURKER_LIMIT = 20;
    static final int MAX_LURKER_LIMIT = 100;

    private final ReaderQueryRepository repository;

    public ReaderService(ReaderQueryRepository repository) {
        this.repository = repository;
    }

    private static void validate(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new InvalidReaderQueryException("'to' must not be before 'from'");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new InvalidReaderQueryException("The date range must not exceed " + MAX_DAYS + " days");
        }
    }

    /** 读者分析的默认数据范围：登录/发帖行为的起止日；没有数据时 from/to 都是 null。 */
    public DataRange range() {
        return repository.loginPostSpan().map(span -> new DataRange(span.from(), span.to())).orElse(new DataRange(null, null));
    }

    public ActivityReport activity(LocalDate from, LocalDate to, Granularity granularity) {
        validate(from, to);
        List<Member> members = repository.members();
        // 汇总里的 MAU 要往前看 30 天，可能早于区间起点
        LocalDate fetchFrom = to.minusDays(ActivityAggregator.MAU_DAYS - 1L).isBefore(from)
            ? to.minusDays(ActivityAggregator.MAU_DAYS - 1L) : from;
        List<ActiveDay> days = repository.activeDays(fetchFrom, to);
        return new ActivityReport(from, to, granularity, ActivityAggregator.summary(members, days, to),
            ActivityAggregator.series(members, days, from, to, granularity));
    }

    public RetentionReport retention(Granularity granularity, LocalDate from, LocalDate to, Integer periods) {
        validate(from, to);
        if (granularity != Granularity.WEEK && granularity != Granularity.MONTH) {
            throw new InvalidReaderQueryException("Retention supports WEEK and MONTH only");
        }
        int count = periods != null ? periods : (granularity == Granularity.WEEK ? WEEKLY_PERIODS : MONTHLY_PERIODS);
        if (count < 1 || count > MAX_PERIODS) {
            throw new InvalidReaderQueryException("periods must be between 1 and " + MAX_PERIODS);
        }
        LocalDate dataEnd = repository.latestActiveDay().orElse(from.minusDays(1));
        List<ActiveDay> days = dataEnd.isBefore(from) ? List.of() : repository.activeDays(from, dataEnd);
        return new RetentionReport(from, to, granularity, count, dataEnd,
            CohortBuilder.build(repository.members(), days, from, to, granularity, count, dataEnd));
    }

    public FunnelReport funnel(LocalDate from, LocalDate to) {
        validate(from, to);
        List<FunnelFacts> facts = repository.funnelFacts(from, to);
        return new FunnelReport(from, to, facts.size(), FunnelBuilder.build(facts));
    }

    public SegmentReport segments(LocalDate from, LocalDate to) {
        validate(from, to);
        Map<Long, MemberActivity> activity = new HashMap<>();
        repository.memberActivity(from, to).forEach(a -> activity.put(a.employeeId(), a));

        Map<ReaderRole, Long> counts = new EnumMap<>(ReaderRole.class);
        long members = 0;
        for (Member member : repository.members()) {
            if (!member.isMemberOn(to)) {
                continue;
            }
            members++;
            MemberActivity a = activity.get(member.id());
            ReaderRole role = a == null ? ReaderRole.INACTIVE
                : ReaderRole.classify(a.posts(), a.interactions(), a.views(), a.events() > 0);
            counts.merge(role, 1L, Long::sum);
        }
        List<SegmentCount> segments = new ArrayList<>();
        for (ReaderRole role : ReaderRole.values()) {
            long count = counts.getOrDefault(role, 0L);
            segments.add(new SegmentCount(role, role.label(), count, members == 0 ? null : count / (double) members));
        }
        return new SegmentReport(from, to, members, segments);
    }

    /** 导出 CSV 的行数上限，防止一次拉出过大的结果。 */
    static final int MAX_EXPORT_ROWS = 50_000;

    /** 区间内全部沉默读者（不分页，最多 {@link #MAX_EXPORT_ROWS} 行），导出 CSV 用。 */
    public List<ReaderQueryRepository.Lurker> lurkersExport(LocalDate from, LocalDate to, String dept) {
        validate(from, to);
        String deptFilter = dept == null || dept.isBlank() ? null : dept.trim();
        return repository.lurkers(from, to, deptFilter, MAX_EXPORT_ROWS, 0).lurkers();
    }

    public LurkerReport lurkers(LocalDate from, LocalDate to, String dept, Integer limit, Integer offset) {
        validate(from, to);
        int pageSize = limit != null ? limit : DEFAULT_LURKER_LIMIT;
        int skip = offset != null ? offset : 0;
        if (pageSize < 1 || pageSize > MAX_LURKER_LIMIT) {
            throw new InvalidReaderQueryException("limit must be between 1 and " + MAX_LURKER_LIMIT);
        }
        if (skip < 0) {
            throw new InvalidReaderQueryException("offset must not be negative");
        }
        String deptFilter = dept == null || dept.isBlank() ? null : dept.trim();
        LurkerPage page = repository.lurkers(from, to, deptFilter, pageSize, skip);
        return new LurkerReport(from, to, page.total(), pageSize, skip, page.lurkers(), repository.depts());
    }
}
