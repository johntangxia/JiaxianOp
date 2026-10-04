package com.jiaxian.analytics.pv;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** 编排：校验参数 → 多取一年数据 → 分桶补零 → 同环比与预测 → 裁剪成请求范围。 */
@Service
public class PvAnalyticsService {

    static final int MAX_FORECAST_PERIODS = 12;
    static final int MAX_LIMIT = 100;

    private final PvDailyQueryRepository repository;
    private final Clock clock;

    public PvAnalyticsService(PvDailyQueryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public PvTrend trend(Granularity granularity, LocalDate from, LocalDate to,
                         PvFilter filter, int forecastPeriods) {
        requireOrderedRange(from, to);
        if (forecastPeriods < 0 || forecastPeriods > MAX_FORECAST_PERIODS) {
            throw new InvalidPvQueryException("forecastPeriods must be between 0 and " + MAX_FORECAST_PERIODS);
        }

        LocalDate today = LocalDate.now(clock);
        LocalDate visibleFrom = granularity.periodStart(from);
        // 往前多取一年，让范围内最早的点也能算同比；再多一周是为了 WEEK 粒度（52 周 = 364 天）对齐不丢点。
        LocalDate fetchFrom = visibleFrom.minusYears(1).minusWeeks(1);
        LocalDate fetchTo = to.isAfter(today) ? today : to;

        List<DailyPv> daily = fetchFrom.isAfter(fetchTo)
            ? List.of() : repository.dailyPv(fetchFrom, fetchTo, filter);
        List<PvBucket> buckets = PvSeriesBuilder.build(daily, granularity, fetchFrom, to, today);
        PvTrendAnalyzer.Analysis analysis =
            PvTrendAnalyzer.analyze(buckets, granularity, visibleFrom, forecastPeriods);

        return new PvTrend(granularity, from, to, analysis.points(), analysis.forecast());
    }

    public List<PvTopEntry> top(PvDimension dimension, LocalDate from, LocalDate to,
                                PvFilter filter, int limit) {
        requireOrderedRange(from, to);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidPvQueryException("limit must be between 1 and " + MAX_LIMIT);
        }
        return repository.top(dimension, from, to, filter, limit);
    }

    private static void requireOrderedRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new InvalidPvQueryException("from must not be after to");
        }
    }
}
