package com.jiaxian.analytics.drill;

import com.jiaxian.analytics.channel.ChannelCockpitService;
import com.jiaxian.analytics.channel.ChannelCockpitService.ChannelSnapshot;
import com.jiaxian.analytics.channel.ChannelQueryRepository.ContentSide;
import com.jiaxian.analytics.channel.ChannelQueryRepository.DailyEntry;
import com.jiaxian.analytics.channel.ChannelQueryRepository.DailySource;
import com.jiaxian.analytics.channel.ContentMetricsFactory;
import com.jiaxian.analytics.channel.DistributionMetrics;
import com.jiaxian.analytics.channel.MixSlice;
import com.jiaxian.analytics.channel.SourceMix;
import com.jiaxian.analytics.channel.SupplyMetrics;
import com.jiaxian.analytics.domain.Channel;
import com.jiaxian.analytics.drill.SubjectQueryRepository.AccountInfo;
import com.jiaxian.analytics.drill.SubjectQueryRepository.BloggerInfo;
import com.jiaxian.analytics.drill.SubjectQueryRepository.ChannelInfo;
import com.jiaxian.analytics.drill.SubjectQueryRepository.ContentInfo;
import com.jiaxian.analytics.drill.SubjectQueryRepository.DailyFollow;
import com.jiaxian.analytics.drill.SubjectQueryRepository.PersonInfo;
import com.jiaxian.analytics.pv.Granularity;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 下钻与对比：按频道、账号、博主、内容逐级取指标、趋势、构成和 Top 内容，以及同类对象的并排对比。 */
@Service
public class DrillService {

    static final int MAX_DAYS = 366;
    static final int TOP_CONTENT_LIMIT = 10;
    static final int MIN_COMPARE = 2;
    static final int MAX_COMPARE = 5;

    private final SubjectQueryRepository repository;
    private final ChannelCockpitService cockpitService;

    public DrillService(SubjectQueryRepository repository, ChannelCockpitService cockpitService) {
        this.repository = repository;
        this.cockpitService = cockpitService;
    }

    /** 当期和上一个等长周期。 */
    private record Periods(LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo) {
    }

    private static Periods periods(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new InvalidDrillQueryException("'to' must not be before 'from'");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_DAYS) {
            throw new InvalidDrillQueryException("The date range must not exceed " + MAX_DAYS + " days");
        }
        LocalDate previousTo = from.minusDays(1);
        return new Periods(from, to, previousTo.minusDays(days - 1), previousTo);
    }

    // ---- 频道 → 账号 ----

    public ChannelAccounts channelAccounts(long channelId, LocalDate from, LocalDate to) {
        Periods p = periods(from, to);
        ChannelInfo channel = repository.channel(channelId).orElseThrow(() -> new DrillNotFoundException("Channel", channelId));
        if (Channel.isCompositeCategory(channel.category())) {
            throw new InvalidDrillQueryException("Composite channels have no content of their own to drill into");
        }
        List<AccountInfo> accounts = repository.accountsOfChannel(channelId);
        SubjectFilter filter = SubjectFilter.NONE.withChannel(channelId);
        List<Long> ids = accounts.stream().map(AccountInfo::id).toList();
        Map<Long, SubjectMetrics> current = metrics(SubjectType.ACCOUNT, filter, p.from(), p.to(), ids);
        Map<Long, SubjectMetrics> previous = metrics(SubjectType.ACCOUNT, filter, p.previousFrom(), p.previousTo(), ids);
        List<BreakdownRow> rows = new ArrayList<>();
        for (AccountInfo account : accounts) {
            rows.add(new BreakdownRow(account.id(), account.name(), account.type(), current.get(account.id()),
                previous.get(account.id())));
        }
        sortByPv(rows);
        return new ChannelAccounts(channelId, channel.name(), p.from(), p.to(), p.previousFrom(), p.previousTo(), rows);
    }

    // ---- 账号 ----

    public AccountOverview account(long accountId, LocalDate from, LocalDate to, Granularity granularity) {
        Periods p = periods(from, to);
        AccountInfo account = repository.account(accountId).orElseThrow(() -> new DrillNotFoundException("Account", accountId));
        SubjectFilter self = SubjectFilter.ofIds(accountId);
        SubjectMetrics current = metrics(SubjectType.ACCOUNT, self, p.from(), p.to(), List.of(accountId)).get(accountId);
        SubjectMetrics previous = metrics(SubjectType.ACCOUNT, self, p.previousFrom(), p.previousTo(), List.of(accountId)).get(accountId);

        List<PersonInfo> authors = repository.authorsOfAccount(accountId);
        List<Long> authorIds = authors.stream().map(PersonInfo::id).toList();
        SubjectFilter inAccount = SubjectFilter.NONE.withAccount(accountId);
        Map<Long, SubjectMetrics> authorNow = metrics(SubjectType.BLOGGER, inAccount, p.from(), p.to(), authorIds);
        Map<Long, SubjectMetrics> authorBefore = metrics(SubjectType.BLOGGER, inAccount, p.previousFrom(), p.previousTo(), authorIds);
        List<BreakdownRow> authorRows = new ArrayList<>();
        for (PersonInfo author : authors) {
            authorRows.add(new BreakdownRow(author.id(), author.name(), null, authorNow.get(author.id()), authorBefore.get(author.id())));
        }
        sortByPv(authorRows);

        List<DrillTrendPoint> trend = trend(SubjectType.ACCOUNT, accountId, p, granularity, true);
        return new AccountOverview(account, p.from(), p.to(), p.previousFrom(), p.previousTo(), granularity, current,
            previous, trend, composition(current), authorRows,
            channelRows(SubjectFilter.NONE.withAccount(accountId), p),
            repository.topContents(SubjectType.ACCOUNT, self, p.from(), p.to(), TOP_CONTENT_LIMIT));
    }

    // ---- 博主 ----

    public BloggerOverview blogger(long employeeId, LocalDate from, LocalDate to, Granularity granularity) {
        Periods p = periods(from, to);
        BloggerInfo blogger = repository.blogger(employeeId).orElseThrow(() -> new DrillNotFoundException("Blogger", employeeId));
        SubjectFilter self = SubjectFilter.ofIds(employeeId);
        SubjectMetrics current = metrics(SubjectType.BLOGGER, self, p.from(), p.to(), List.of(employeeId)).get(employeeId);
        SubjectMetrics previous = metrics(SubjectType.BLOGGER, self, p.previousFrom(), p.previousTo(), List.of(employeeId)).get(employeeId);

        SubjectFilter byAuthor = SubjectFilter.NONE.withAuthor(employeeId);
        List<BreakdownRow> accountRows = breakdown(SubjectType.ACCOUNT, byAuthor, p);
        return new BloggerOverview(blogger, p.from(), p.to(), p.previousFrom(), p.previousTo(), granularity, current,
            previous, trend(SubjectType.BLOGGER, employeeId, p, granularity, false), composition(current), accountRows,
            channelRows(byAuthor, p),
            repository.topContents(SubjectType.BLOGGER, self, p.from(), p.to(), TOP_CONTENT_LIMIT));
    }

    // ---- 内容 ----

    public ContentOverview content(long contentId, LocalDate from, LocalDate to, Granularity granularity) {
        Periods p = periods(from, to);
        ContentInfo content = repository.content(contentId).orElseThrow(() -> new DrillNotFoundException("Content", contentId));
        SubjectFilter self = SubjectFilter.ofIds(contentId);
        SubjectMetrics current = metrics(SubjectType.CONTENT, self, p.from(), p.to(), List.of(contentId)).get(contentId);
        SubjectMetrics previous = metrics(SubjectType.CONTENT, self, p.previousFrom(), p.previousTo(), List.of(contentId)).get(contentId);
        return new ContentOverview(content, repository.authorsOfContent(contentId), repository.tags(contentId), p.from(),
            p.to(), p.previousFrom(), p.previousTo(), granularity, current, previous,
            trend(SubjectType.CONTENT, contentId, p, granularity, false), composition(current),
            repository.entryExposure(contentId, p.from(), p.to()));
    }

    // ---- 对比 ----

    public Comparison compare(SubjectType type, List<Long> ids, LocalDate from, LocalDate to, Granularity granularity) {
        Periods p = periods(from, to);
        if (type == SubjectType.CONTENT) {
            throw new InvalidDrillQueryException("Comparison supports CHANNEL, ACCOUNT and BLOGGER only");
        }
        if (ids.size() < MIN_COMPARE || ids.size() > MAX_COMPARE || new HashSet<>(ids).size() != ids.size()) {
            throw new InvalidDrillQueryException("Compare between " + MIN_COMPARE + " and " + MAX_COMPARE
                + " distinct " + type.name().toLowerCase() + " ids");
        }
        Map<Long, String> names = new HashMap<>();
        Map<Long, String> extras = new HashMap<>();
        switch (type) {
            case CHANNEL -> {
                for (ChannelInfo channel : repository.channels(ids)) {
                    if (Channel.isCompositeCategory(channel.category())) {
                        throw new InvalidDrillQueryException("Composite channels have no content to compare: " + channel.name());
                    }
                    names.put(channel.id(), channel.name());
                    extras.put(channel.id(), channel.category());
                }
            }
            case ACCOUNT -> repository.accounts(ids).forEach(a -> {
                names.put(a.id(), a.name());
                extras.put(a.id(), a.type());
            });
            case BLOGGER -> repository.employees(ids).forEach(e -> names.put(e.id(), e.name()));
            default -> throw new IllegalStateException();
        }
        for (long id : ids) {
            if (!names.containsKey(id)) {
                throw new DrillNotFoundException(type.name().charAt(0) + type.name().substring(1).toLowerCase(), id);
            }
        }

        SubjectFilter filter = SubjectFilter.ofIds(ids);
        Map<Long, SubjectMetrics> now = metrics(type, filter, p.from(), p.to(), ids);
        Map<Long, SubjectMetrics> before = metrics(type, filter, p.previousFrom(), p.previousTo(), ids);
        Map<Long, DistributionMetrics> ownEntry = new HashMap<>();
        if (type == SubjectType.CHANNEL) {
            for (ChannelSnapshot snapshot : cockpitService.snapshot(p.from(), p.to())) {
                ownEntry.put(snapshot.id(), snapshot.period().distribution());
            }
        }
        List<CompareSubject> subjects = new ArrayList<>();
        for (long id : ids) {
            subjects.add(new CompareSubject(id, names.get(id), extras.get(id), now.get(id), before.get(id),
                ownEntry.get(id), trend(type, id, p, granularity, type == SubjectType.ACCOUNT)));
        }
        return new Comparison(type, p.from(), p.to(), p.previousFrom(), p.previousTo(), granularity, subjects);
    }

    // ---- 装配 ----

    /** 一批对象的指标；mustInclude 里的对象即使这个周期没有任何数据也会有一份全空的指标。 */
    private Map<Long, SubjectMetrics> metrics(SubjectType type, SubjectFilter filter, LocalDate from, LocalDate to,
                                              Collection<Long> mustInclude) {
        Map<Long, ContentSide> sides = repository.contentSide(type, filter, from, to);
        Map<Long, Long> interactions = repository.interactions(type, filter, from, to);
        Map<Long, SupplyMetrics> supply = repository.supply(type, filter, from, to);
        Map<Long, List<Long>> contentPv = repository.contentPv(type, filter, from, to);
        Map<Long, ExposureMetrics> exposure = repository.exposure(type, filter, from, to);

        Set<Long> keys = new HashSet<>(mustInclude);
        keys.addAll(sides.keySet());
        keys.addAll(interactions.keySet());
        keys.addAll(supply.keySet());
        keys.addAll(exposure.keySet());
        Map<Long, FollowerMetrics> followers =
            type == SubjectType.ACCOUNT ? repository.followers(keys, from, to) : Map.of();

        Map<Long, SubjectMetrics> result = new HashMap<>();
        for (long key : keys) {
            result.put(key, new SubjectMetrics(
                ContentMetricsFactory.build(sides.get(key), interactions.getOrDefault(key, 0L),
                    contentPv.getOrDefault(key, List.of())),
                supply.getOrDefault(key, SupplyMetrics.EMPTY), exposure.getOrDefault(key, ExposureMetrics.EMPTY),
                type == SubjectType.ACCOUNT ? followers.getOrDefault(key, FollowerMetrics.EMPTY) : null));
        }
        return result;
    }

    /** 当期有数据的下一级对象（账号、频道等），按 PV 从高到低；上期没有数据的对象上期指标是全空的。 */
    private List<BreakdownRow> breakdown(SubjectType type, SubjectFilter filter, Periods p) {
        Map<Long, SubjectMetrics> now = metrics(type, filter, p.from(), p.to(), List.of());
        Map<Long, SubjectMetrics> before = metrics(type, filter, p.previousFrom(), p.previousTo(), now.keySet());
        List<BreakdownRow> rows = new ArrayList<>();
        Map<Long, String> names = new HashMap<>();
        Map<Long, String> extras = new HashMap<>();
        switch (type) {
            case ACCOUNT -> repository.accounts(now.keySet()).forEach(a -> {
                names.put(a.id(), a.name());
                extras.put(a.id(), a.type());
            });
            case CHANNEL -> repository.channels(now.keySet()).forEach(c -> {
                names.put(c.id(), c.name());
                extras.put(c.id(), c.category());
            });
            default -> throw new IllegalStateException();
        }
        for (long id : now.keySet()) {
            rows.add(new BreakdownRow(id, names.getOrDefault(id, "未知"), extras.get(id), now.get(id), before.get(id)));
        }
        sortByPv(rows);
        return rows;
    }

    private List<BreakdownRow> channelRows(SubjectFilter filter, Periods p) {
        return breakdown(SubjectType.CHANNEL, filter, p);
    }

    private static void sortByPv(List<BreakdownRow> rows) {
        rows.sort(Comparator.comparingLong((BreakdownRow r) -> r.current().content().pv()).reversed()
            .thenComparingLong(BreakdownRow::id));
    }

    private static List<MixSlice> composition(SubjectMetrics metrics) {
        SourceMix mix = metrics.content().sourceMix();
        return List.of(new MixSlice("OWN_ENTRY", "自主入口", mix.ownEntry()),
            new MixSlice("COMPOSITE_ENTRY", "综合入口", mix.compositeEntry()),
            new MixSlice("SEARCH", "搜索", mix.search()), new MixSlice("SHARE", "分享", mix.share()),
            new MixSlice("OTHER", "其他", mix.other()));
    }

    private List<DrillTrendPoint> trend(SubjectType type, long id, Periods p, Granularity granularity, boolean withFollowers) {
        List<DailySource> sources = repository.dailySource(type, id, p.from(), p.to());
        List<DailyEntry> exposure = repository.dailyExposure(type, id, p.from(), p.to());
        List<DailyFollow> follows = withFollowers ? repository.dailyFollows(id, p.from(), p.to()) : List.of();

        Map<LocalDate, long[]> buckets = new TreeMap<>();
        for (LocalDate start = granularity.periodStart(p.from()); !start.isAfter(p.to());
             start = granularity.nextPeriodStart(start)) {
            buckets.put(start, new long[9]);
        }
        for (DailySource day : sources) {
            long[] b = buckets.get(granularity.periodStart(day.day()));
            b[0] += day.ownEntry();
            b[1] += day.compositeEntry();
            b[2] += day.search();
            b[3] += day.share();
            b[4] += day.other();
        }
        for (DailyEntry day : exposure) {
            long[] b = buckets.get(granularity.periodStart(day.day()));
            b[5] += day.impressions();
            b[6] += day.clicks();
        }
        for (DailyFollow day : follows) {
            long[] b = buckets.get(granularity.periodStart(day.day()));
            b[7] += day.newFollowers();
            b[8] += day.lostFollowers();
        }
        List<DrillTrendPoint> points = new ArrayList<>();
        buckets.forEach((start, b) -> points.add(new DrillTrendPoint(start, b[0], b[1], b[2], b[3], b[4], b[5], b[6],
            b[5] == 0 ? null : b[6] / (double) b[5], b[7], b[8])));
        return points;
    }
}
