package com.jiaxian.analytics.channel;

import com.jiaxian.analytics.channel.ChannelQueryRepository.ChannelRef;
import com.jiaxian.analytics.channel.ChannelQueryRepository.ContentSide;
import com.jiaxian.analytics.channel.ChannelQueryRepository.DailyEntry;
import com.jiaxian.analytics.channel.ChannelQueryRepository.DailySource;
import com.jiaxian.analytics.domain.Channel;
import com.jiaxian.analytics.pv.Granularity;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 装配频道驾驶舱：当期和上一等长周期的指标、同类基准中位数、自动诊断，以及单频道详情。 */
@Service
public class ChannelCockpitService {

    static final int MAX_DAYS = 366;
    static final int TOP_CONTENT_LIMIT = 10;
    static final int MAX_COMPOSITION_SLICES = 8;

    private final ChannelQueryRepository repository;

    public ChannelCockpitService(ChannelQueryRepository repository) {
        this.repository = repository;
    }

    /** 一个周期里所有频道的原始聚合结果。 */
    private record PeriodData(Map<Long, DistributionMetrics> distribution, Map<Long, ContentSide> contentSide,
                              Map<Long, Long> interactions, Map<Long, SupplyMetrics> supply,
                              Map<Long, List<Long>> contentPv, Map<Long, Map<Long, Long>> compositeClicks) {
    }

    private PeriodData load(LocalDate from, LocalDate to) {
        return new PeriodData(repository.distribution(from, to), repository.contentSide(from, to),
            repository.interactions(from, to), repository.supply(from, to), repository.contentPv(from, to),
            repository.compositeClicksByDomain(from, to));
    }

    private static void validate(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new InvalidChannelQueryException("'to' must not be before 'from'");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new InvalidChannelQueryException("The date range must not exceed " + MAX_DAYS + " days");
        }
    }

    /** 一个频道在一个周期里的指标（不含上期和诊断）。 */
    public record ChannelSnapshot(long id, String name, String category, ChannelPeriod period) {
    }

    /** 区间内每个综合/领域频道的指标，按频道 id 排序；供竞赛榜这类只关心某一个周期的调用方使用。 */
    public List<ChannelSnapshot> snapshot(LocalDate from, LocalDate to) {
        validate(from, to);
        PeriodData data = load(from, to);
        List<ChannelSnapshot> result = new ArrayList<>();
        for (ChannelRef channel : repository.channels()) {
            result.add(new ChannelSnapshot(channel.id(), channel.name(), channel.category(),
                period(channel.id(), isComposite(channel), data)));
        }
        return result;
    }

    public Cockpit cockpit(LocalDate from, LocalDate to) {
        validate(from, to);
        LocalDate previousTo = from.minusDays(1);
        LocalDate previousFrom = previousTo.minusDays(ChronoUnit.DAYS.between(from, to));
        PeriodData current = load(from, to);
        PeriodData previous = load(previousFrom, previousTo);

        List<ChannelRef> channels = repository.channels();
        Map<Long, ChannelPeriod> currentPeriods = new HashMap<>();
        Map<Long, ChannelPeriod> previousPeriods = new HashMap<>();
        List<ChannelPeriod> compositeCurrent = new ArrayList<>();
        List<ChannelPeriod> domainCurrent = new ArrayList<>();
        for (ChannelRef channel : channels) {
            boolean composite = isComposite(channel);
            ChannelPeriod cur = period(channel.id(), composite, current);
            currentPeriods.put(channel.id(), cur);
            previousPeriods.put(channel.id(), period(channel.id(), composite, previous));
            (composite ? compositeCurrent : domainCurrent).add(cur);
        }

        int domainsWithContent = (int) domainCurrent.stream().filter(p -> p.content().pv() > 0).count();
        Map<String, Double> domainBenchmark = Medians.domain(domainCurrent);
        Map<String, Double> compositeBenchmark = Medians.composite(compositeCurrent, domainsWithContent);

        List<ChannelRow> compositeRows = new ArrayList<>();
        List<ChannelRow> domainRows = new ArrayList<>();
        for (ChannelRef channel : channels) {
            ChannelPeriod cur = currentPeriods.get(channel.id());
            ChannelPeriod prev = previousPeriods.get(channel.id());
            if (isComposite(channel)) {
                compositeRows.add(new ChannelRow(channel.id(), channel.name(), channel.category(), cur, prev,
                    ChannelDiagnoser.diagnoseComposite(cur, prev, compositeBenchmark)));
            } else {
                domainRows.add(new ChannelRow(channel.id(), channel.name(), channel.category(), cur, prev,
                    ChannelDiagnoser.diagnoseDomain(cur, prev, domainBenchmark)));
            }
        }
        return new Cockpit(from, to, previousFrom, previousTo, compositeRows, domainRows, compositeBenchmark,
            domainBenchmark);
    }

    public ChannelDetail detail(long channelId, LocalDate from, LocalDate to, Granularity granularity) {
        Cockpit cockpit = cockpit(from, to);   // 同时完成参数校验
        ChannelRow row = java.util.stream.Stream.concat(cockpit.composite().stream(), cockpit.domain().stream())
            .filter(r -> r.id() == channelId).findFirst().orElseThrow(() -> new ChannelNotFoundException(channelId));
        boolean composite = Channel.isCompositeCategory(row.category());

        List<DailySource> sources = composite ? List.of() : repository.dailyContentPv(channelId, from, to);
        List<DailyEntry> entries = repository.dailyEntry(channelId, from, to);
        return new ChannelDetail(row, granularity, trend(sources, entries, from, to, granularity),
            composition(row, composite, cockpit), repository.topContents(channelId, composite, from, to, TOP_CONTENT_LIMIT));
    }

    // ---- 装配 ----

    private static boolean isComposite(ChannelRef channel) {
        return Channel.isCompositeCategory(channel.category());
    }

    private ChannelPeriod period(long channelId, boolean composite, PeriodData data) {
        DistributionMetrics distribution = data.distribution().getOrDefault(channelId, DistributionMetrics.EMPTY);
        if (composite) {
            return new ChannelPeriod(distribution, null, null, coverage(data.compositeClicks().get(channelId)));
        }
        return new ChannelPeriod(distribution, content(channelId, data),
            data.supply().getOrDefault(channelId, SupplyMetrics.EMPTY), null);
    }

    private static ContentMetrics content(long channelId, PeriodData data) {
        return ContentMetricsFactory.build(data.contentSide().get(channelId),
            data.interactions().getOrDefault(channelId, 0L), data.contentPv().getOrDefault(channelId, List.of()));
    }

    private static CompositeCoverage coverage(Map<Long, Long> clicksByDomain) {
        if (clicksByDomain == null || clicksByDomain.isEmpty()) {
            return CompositeCoverage.EMPTY;
        }
        long total = clicksByDomain.values().stream().mapToLong(Long::longValue).sum();
        long top = clicksByDomain.values().stream().mapToLong(Long::longValue).max().orElse(0);
        return new CompositeCoverage(clicksByDomain.size(), total == 0 ? null : top / (double) total);
    }

    private static List<TrendPoint> trend(List<DailySource> sources, List<DailyEntry> entries, LocalDate from,
                                          LocalDate to, Granularity granularity) {
        Map<LocalDate, long[]> buckets = new java.util.TreeMap<>();
        for (LocalDate start = granularity.periodStart(from); !start.isAfter(to);
             start = granularity.nextPeriodStart(start)) {
            buckets.put(start, new long[7]);
        }
        for (DailySource day : sources) {
            long[] b = buckets.get(granularity.periodStart(day.day()));
            b[0] += day.ownEntry();
            b[1] += day.compositeEntry();
            b[2] += day.search();
            b[3] += day.share();
            b[4] += day.other();
        }
        for (DailyEntry day : entries) {
            long[] b = buckets.get(granularity.periodStart(day.day()));
            b[5] += day.impressions();
            b[6] += day.clicks();
        }
        List<TrendPoint> points = new ArrayList<>();
        buckets.forEach((start, b) -> points.add(new TrendPoint(start, b[0], b[1], b[2], b[3], b[4], b[5], b[6],
            b[5] == 0 ? null : b[6] / (double) b[5])));
        return points;
    }

    private List<MixSlice> composition(ChannelRow row, boolean composite, Cockpit cockpit) {
        List<MixSlice> slices = new ArrayList<>();
        if (!composite) {
            SourceMix mix = row.current().content().sourceMix();
            slices.add(new MixSlice("OWN_ENTRY", "自主入口", mix.ownEntry()));
            slices.add(new MixSlice("COMPOSITE_ENTRY", "综合入口", mix.compositeEntry()));
            slices.add(new MixSlice("SEARCH", "搜索", mix.search()));
            slices.add(new MixSlice("SHARE", "分享", mix.share()));
            slices.add(new MixSlice("OTHER", "其他", mix.other()));
            return slices;
        }
        Map<Long, String> names = new HashMap<>();
        cockpit.domain().forEach(r -> names.put(r.id(), r.name()));
        Map<Long, Long> byDomain = repository.compositeClicksByDomain(cockpit.from(), cockpit.to()).get(row.id());
        if (byDomain == null) {
            return slices;
        }
        List<Map.Entry<Long, Long>> sorted = new ArrayList<>(byDomain.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        long rest = 0;
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<Long, Long> entry = sorted.get(i);
            if (i < MAX_COMPOSITION_SLICES) {
                slices.add(new MixSlice(String.valueOf(entry.getKey()), names.getOrDefault(entry.getKey(), "未知"),
                    entry.getValue()));
            } else {
                rest += entry.getValue();
            }
        }
        if (rest > 0) {
            slices.add(new MixSlice("OTHER", "其他领域", rest));
        }
        return slices;
    }
}
