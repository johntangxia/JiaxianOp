package com.jiaxian.analytics.kol;

import com.jiaxian.analytics.pv.Granularity;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 博主详情页：校验参数、解析默认范围、组装 profile，并把按天的行聚成连续周期（补零）。 */
@Service
public class BloggerProfileService {

    static final int MAX_WEEKS = 104;
    static final int MAX_PERIODS = 400;
    static final int RECENT_EVENT_LIMIT = 20;

    private record Range(Granularity granularity, LocalDate from, LocalDate to, LocalDate fetchTo,
                         List<PeriodAxis.Period> periods) {
    }

    private final BloggerProfileRepository repository;
    private final KolRfmRepository rfmRepository;
    private final PlaybookService playbookService;
    private final Clock clock;

    public BloggerProfileService(BloggerProfileRepository repository, KolRfmRepository rfmRepository,
                                 PlaybookService playbookService, Clock clock) {
        this.repository = repository;
        this.rfmRepository = rfmRepository;
        this.playbookService = playbookService;
        this.clock = clock;
    }

    public BloggerProfile profile(long employeeId, int weeks) {
        if (weeks < 1 || weeks > MAX_WEEKS) {
            throw new InvalidKolQueryException("weeks must be between 1 and " + MAX_WEEKS);
        }
        EmployeeInfo employee = requireEmployee(employeeId);
        List<RecentEvent> recentEvents = repository.recentEvents(employeeId, RECENT_EVENT_LIMIT);

        Optional<LocalDate> latest = rfmRepository.latestAsOf();
        if (latest.isEmpty()) {
            return new BloggerProfile(employee, null, List.of(), List.of(), recentEvents);
        }
        LocalDate asOf = latest.get();
        // 窗口覆盖最近 weeks 次每周重算：[asOf − 7×(weeks−1) 天, asOf]
        List<HistoryPoint> history = repository.history(employeeId, asOf.minusDays(7L * (weeks - 1)), asOf);
        PlaybookLookup lookup = playbookService.lookup();
        CurrentStanding current = repository.standing(employeeId, asOf)
            .map(row -> new CurrentStanding(asOf, row.rank(), row.segment(), row.segment().displayName(),
                row.churnAlert(), lookup.find(row.segment(), row.churnAlert()), row.rollingPoints12m(),
                row.recencyDays(), row.frequency(), row.monetary(), row.rScore(), row.fScore(), row.mScore(),
                row.alert()))
            .orElse(null);
        return new BloggerProfile(employee, current, history, SegmentTrajectory.changes(history), recentEvents);
    }

    public ActivitySeries activity(long employeeId, Granularity granularity, LocalDate from, LocalDate to) {
        Range range = resolve(granularity, from, to);
        requireEmployee(employeeId);

        Map<LocalDate, long[]> sums = new HashMap<>();
        if (!range.periods().isEmpty()) {
            for (BloggerProfileRepository.DailyActivity row : repository.dailyActivity(
                    employeeId, range.periods().get(0).start(), range.fetchTo())) {
                long[] counts = sums.computeIfAbsent(range.granularity().periodStart(row.date()), k -> new long[3]);
                switch (row.type()) {
                    case "POST" -> counts[0] += row.count();
                    case "COMMENT" -> counts[1] += row.count();
                    case "LOGIN" -> counts[2] += row.count();
                    default -> {
                    }
                }
            }
        }
        List<ActivityPoint> points = range.periods().stream().map(period -> {
            long[] counts = sums.getOrDefault(period.start(), new long[3]);
            return new ActivityPoint(period.start(), counts[0], counts[1], counts[2], period.complete());
        }).toList();
        return new ActivitySeries(range.granularity(), range.from(), range.to(), points);
    }

    public PointsTrend pointsTrend(long employeeId, Granularity granularity, LocalDate from, LocalDate to) {
        Range range = resolve(granularity, from, to);
        requireEmployee(employeeId);

        Map<LocalDate, long[]> sums = new HashMap<>();
        if (!range.periods().isEmpty()) {
            for (BloggerProfileRepository.DailyPoints row : repository.dailyPoints(
                    employeeId, range.periods().get(0).start(), range.fetchTo())) {
                long[] points = sums.computeIfAbsent(range.granularity().periodStart(row.date()), k -> new long[2]);
                if ("ACTIVE".equals(row.category())) {
                    points[0] += row.points();
                } else if ("PASSIVE".equals(row.category())) {
                    points[1] += row.points();
                }
            }
        }
        List<PointsTrendPoint> points = range.periods().stream().map(period -> {
            long[] sum = sums.getOrDefault(period.start(), new long[2]);
            return new PointsTrendPoint(period.start(), sum[0], sum[1], sum[0] + sum[1], period.complete());
        }).toList();
        return new PointsTrend(range.granularity(), range.from(), range.to(), points);
    }

    private EmployeeInfo requireEmployee(long employeeId) {
        return repository.employee(employeeId).orElseThrow(() -> new EmployeeNotFoundException(employeeId));
    }

    /** 解析默认值并校验：granularity 默认 WEEK，to 默认今天，from 默认往前推一个粒度相关的跨度。 */
    private Range resolve(Granularity granularity, LocalDate from, LocalDate to) {
        Granularity effectiveGranularity = granularity == null ? Granularity.WEEK : granularity;
        LocalDate today = LocalDate.now(clock);
        LocalDate effectiveTo = to == null ? today : to;
        LocalDate effectiveFrom = from != null ? from : defaultFrom(effectiveGranularity, effectiveTo);
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new InvalidKolQueryException("from must not be after to");
        }
        List<PeriodAxis.Period> periods =
            PeriodAxis.periods(effectiveGranularity, effectiveFrom, effectiveTo, today, MAX_PERIODS);
        LocalDate fetchTo = effectiveTo.isAfter(today) ? today : effectiveTo;
        return new Range(effectiveGranularity, effectiveFrom, effectiveTo, fetchTo, periods);
    }

    /** DAY 30 天、WEEK 26 周、MONTH 12 个月、YEAR 5 年（含 to 所在的那一个周期）。 */
    private static LocalDate defaultFrom(Granularity granularity, LocalDate to) {
        return switch (granularity) {
            case DAY -> to.minusDays(29);
            case WEEK -> to.minusWeeks(25);
            case MONTH -> to.minusMonths(11);
            case YEAR -> to.minusYears(4);
        };
    }
}
