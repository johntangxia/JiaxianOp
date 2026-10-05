package com.jiaxian.analytics.contest;

import com.jiaxian.analytics.channel.ChannelCockpitService;
import com.jiaxian.analytics.channel.ChannelCockpitService.ChannelSnapshot;
import com.jiaxian.analytics.channel.ChannelPeriod;
import com.jiaxian.analytics.contest.ContestRepository.DataRange;
import com.jiaxian.analytics.domain.Channel;
import com.jiaxian.analytics.pv.Granularity;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 装配竞赛榜：本期和上一个自然周期各算一次健康分，得出名次、名次变化和进步榜。 */
@Service
public class ContestService {

    static final int MAX_PERIODS = 52;
    static final int IMPROVER_LIMIT = 5;

    // 领域榜的指标键
    static final String OWN_ENTRY_SHARE = "OWN_ENTRY_SHARE";
    static final String OWN_CTR = "OWN_CTR";
    static final String PV = "PV";
    static final String INTERACTION_RATE = "INTERACTION_RATE";
    static final String UV = "UV";
    static final String CONTENTS = "CONTENTS";
    // 综合榜的指标键
    static final String CTR = "CTR";
    static final String IMPRESSIONS = "IMPRESSIONS";
    static final String CLICKS = "CLICKS";
    static final String REACH_UV = "REACH_UV";
    static final String DOMAINS_COVERED = "DOMAINS_COVERED";

    public static final List<DimensionDef> DOMAIN_DIMENSIONS = List.of(
        new DimensionDef("OWN_ENTRY", "自主分发", 0.25, List.of(OWN_ENTRY_SHARE, OWN_CTR)),
        new DimensionDef("TRAFFIC", "流量", 0.20, List.of(PV)),
        new DimensionDef("INTERACTION", "互动", 0.20, List.of(INTERACTION_RATE)),
        new DimensionDef("REACH", "触达用户", 0.20, List.of(UV)),
        new DimensionDef("SUPPLY", "内容供给", 0.15, List.of(CONTENTS)));

    public static final List<DimensionDef> COMPOSITE_DIMENSIONS = List.of(
        new DimensionDef("CTR", "点击率", 0.30, List.of(CTR)),
        new DimensionDef("IMPRESSIONS", "曝光", 0.25, List.of(IMPRESSIONS)),
        new DimensionDef("CLICKS", "点击", 0.25, List.of(CLICKS)),
        new DimensionDef("REACH", "触达用户", 0.10, List.of(REACH_UV)),
        new DimensionDef("COVERAGE", "领域覆盖", 0.10, List.of(DOMAINS_COVERED)));

    private final ChannelCockpitService cockpitService;
    private final ContestRepository repository;

    public ContestService(ChannelCockpitService cockpitService, ContestRepository repository) {
        this.cockpitService = cockpitService;
        this.repository = repository;
    }

    /** 可选周期，最新的在前，最多 52 个；没有 PV 数据时为空。 */
    public List<PeriodOption> periods(ContestType type) {
        Optional<DataRange> range = repository.pvDataRange();
        if (range.isEmpty()) {
            return List.of();
        }
        Granularity granularity = type.granularity();
        List<PeriodOption> periods = new ArrayList<>();
        for (LocalDate start = granularity.periodStart(range.get().to());
             !start.isBefore(granularity.periodStart(range.get().from())) && periods.size() < MAX_PERIODS;
             start = granularity.periodStart(start.minusDays(1))) {
            LocalDate end = granularity.periodEnd(start);
            periods.add(new PeriodOption(start, end, !end.isAfter(range.get().to())));
        }
        return periods;
    }

    public ContestBoards boards(ContestType type, LocalDate periodStart) {
        Granularity granularity = type.granularity();
        if (!granularity.periodStart(periodStart).equals(periodStart)) {
            throw new InvalidContestQueryException("periodStart must be the first day of a "
                + (type == ContestType.WEEK ? "week (Monday)" : "month"));
        }
        LocalDate periodEnd = granularity.periodEnd(periodStart);
        LocalDate previousStart = granularity.periodStart(periodStart.minusDays(1));
        LocalDate previousEnd = granularity.periodEnd(previousStart);

        List<ChannelSnapshot> current = cockpitService.snapshot(periodStart, periodEnd);
        List<ChannelSnapshot> previous = cockpitService.snapshot(previousStart, previousEnd);

        boolean previousHasData = previous.stream().anyMatch(s -> hasData(s.period()));
        List<ContestEntry> domain = league(current, previous, false, previousHasData);
        List<ContestEntry> composite = league(current, previous, true, previousHasData);
        List<ContestEntry> improvers = domain.stream()
            .filter(e -> e.scoreChange() != null && e.scoreChange() > 0)
            .sorted(Comparator.comparingDouble(ContestEntry::scoreChange).reversed()
                .thenComparingLong(ContestEntry::channelId))
            .limit(IMPROVER_LIMIT).toList();
        return new ContestBoards(type, periodStart, periodEnd, previousStart, previousEnd, previousHasData, domain,
            composite, improvers);
    }

    // ---- 装配 ----

    private static boolean hasData(ChannelPeriod period) {
        return (period.content() != null && period.content().pv() > 0) || period.distribution().impressions() > 0
            || period.distribution().clicks() > 0;
    }

    private static List<ChannelSnapshot> inLeague(List<ChannelSnapshot> snapshots, boolean composite) {
        return snapshots.stream().filter(s -> Channel.isCompositeCategory(s.category()) == composite).toList();
    }

    private List<ContestEntry> league(List<ChannelSnapshot> current, List<ChannelSnapshot> previous, boolean composite,
                                      boolean previousHasData) {
        List<DimensionDef> dimensions = composite ? COMPOSITE_DIMENSIONS : DOMAIN_DIMENSIONS;
        List<ChannelSnapshot> members = inLeague(current, composite);
        List<ScoredChannel> scored = displayed(ContestScorer.score(candidates(members, composite), dimensions));
        Map<Long, Integer> ranks = ContestScorer.ranks(scored);

        Map<Long, ScoredChannel> previousScored = new HashMap<>();
        Map<Long, Integer> previousRanks = new HashMap<>();
        if (previousHasData) {
            List<ScoredChannel> scoredPrevious =
                displayed(ContestScorer.score(candidates(inLeague(previous, composite), composite), dimensions));
            scoredPrevious.forEach(s -> previousScored.put(s.channelId(), s));
            previousRanks.putAll(ContestScorer.ranks(scoredPrevious));
        }

        List<ContestEntry> entries = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) {
            ChannelSnapshot member = members.get(i);
            ScoredChannel score = scored.get(i);
            int rank = ranks.get(member.id());
            Integer previousRank = previousRanks.get(member.id());
            ScoredChannel before = previousScored.get(member.id());
            List<DimensionScore> rounded = score.dimensions().stream()
                .map(d -> new DimensionScore(d.key(), d.label(), d.weight(), round1(d.score()))).toList();
            entries.add(new ContestEntry(rank, previousRank, previousRank == null ? null : previousRank - rank,
                member.id(), member.name(), round1(score.total()),
                before == null ? null : round1(before.total()),
                before == null ? null : round1(round1(score.total()) - round1(before.total())), rounded,
                strongest(rounded), weakest(rounded)));
        }
        entries.sort(Comparator.comparingInt(ContestEntry::rank).thenComparingLong(ContestEntry::channelId));
        return entries;
    }

    /** 健康分取整到一位小数（页面上显示的就是它），名次按显示的分数排：显示分数相同的频道名次相同。 */
    private static List<ScoredChannel> displayed(List<ScoredChannel> scored) {
        return scored.stream().map(s -> new ScoredChannel(s.channelId(), round1(s.total()), s.dimensions())).toList();
    }

    private static List<Candidate> candidates(List<ChannelSnapshot> members, boolean composite) {
        List<Candidate> candidates = new ArrayList<>();
        for (ChannelSnapshot member : members) {
            ChannelPeriod p = member.period();
            Map<String, Double> metrics = new HashMap<>();
            if (composite) {
                metrics.put(CTR, p.distribution().ctr());
                metrics.put(IMPRESSIONS, (double) p.distribution().impressions());
                metrics.put(CLICKS, (double) p.distribution().clicks());
                metrics.put(REACH_UV, (double) p.distribution().reachUv());
                metrics.put(DOMAINS_COVERED, (double) p.coverage().domainsCovered());
            } else {
                metrics.put(OWN_ENTRY_SHARE, p.content().ownEntryShare());
                metrics.put(OWN_CTR, p.distribution().ctr());
                metrics.put(PV, (double) p.content().pv());
                metrics.put(INTERACTION_RATE, p.content().interactionRate());
                metrics.put(UV, (double) p.content().uv());
                metrics.put(CONTENTS, (double) p.supply().contents());
            }
            candidates.add(new Candidate(member.id(), metrics));
        }
        return candidates;
    }

    private static String strongest(List<DimensionScore> dimensions) {
        DimensionScore best = dimensions.stream().max(Comparator.comparingDouble(DimensionScore::score)).orElse(null);
        return best == null || best.score() <= 0 ? null : best.label();
    }

    private static String weakest(List<DimensionScore> dimensions) {
        return dimensions.stream().min(Comparator.comparingDouble(DimensionScore::score)).map(DimensionScore::label)
            .orElse(null);
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
