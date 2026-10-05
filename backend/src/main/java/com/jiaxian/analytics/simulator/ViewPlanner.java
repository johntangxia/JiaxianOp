package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.ContentType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 需求驱动的浏览规划：浏览由"谁登录了"产生。每次登录产生 Poisson(λ × 倍率) 次浏览，浏览时刻落在登录当天、
 * 登录之后 0–4 小时内；内容从此刻之前 30 天内发布的内容里按"热度^0.6 × 半衰期 2 天的衰减"挑。
 * 来源占比和约束沿用 {@link SourceSampler}：关注入口只选浏览者当时关注的账号的内容，视频入口只选视频，
 * 自主入口限定在选定的领域；挑不出就退回推荐入口；约 70% 的浏览先限定在浏览者所属领域。
 * 纯逻辑，随机数由调用方传入，不访问数据库。
 */
public final class ViewPlanner {

    static final int WINDOW_DAYS = 30;
    static final double POPULARITY_EXPONENT = 0.6;
    static final double AGE_HALF_LIFE_HOURS = 48;
    static final double SAME_DOMAIN_RATIO = 0.7;
    static final int MAX_MINUTES_AFTER_LOGIN = 240;
    static final double MIN_MULTIPLIER = 0.3;
    static final double MAX_MULTIPLIER = 4;
    static final double MULTIPLIER_EXPONENT = 0.35;

    /** 可以被浏览的内容。 */
    public record Candidate(long id, LocalDateTime publishTime, long channelId, String channelName, long accountId,
                            ContentType type, double weight) {
    }

    /** 规划出的一次浏览。 */
    public record PlannedView(long contentId, long viewerId, LocalDateTime time, SourceKind kind, Long entryChannelId,
                              int dwellSeconds, boolean completed) {
    }

    private final List<Candidate> sorted;
    private final Map<String, Long> compositeChannelIds;
    private final Map<Long, List<FollowPlanner.Edge>> edgesByFollower;

    /**
     * @param compositeChannelIds 综合频道名（推荐/热点/视频/关注）到频道 id
     * @param edgesByFollower     员工 id → 他的关注关系
     */
    public ViewPlanner(List<Candidate> contents, Map<String, Long> compositeChannelIds,
                       Map<Long, List<FollowPlanner.Edge>> edgesByFollower) {
        this.sorted = new ArrayList<>(contents);
        this.sorted.sort(Comparator.comparing(Candidate::publishTime).thenComparingLong(Candidate::id));
        this.compositeChannelIds = compositeChannelIds;
        this.edgesByFollower = edgesByFollower;
    }

    /**
     * 每个员工每个登录日的浏览次数倍率：与活跃倾向正相关（倾向^0.35），范围 0.3–4，平均为 1。
     * 返回和 profiles 一一对应。
     */
    public static double[] multipliers(List<EmployeeProfile> profiles) {
        int n = profiles.size();
        double[] result = new double[n];
        if (n == 0) {
            return result;
        }
        double meanPropensity = profiles.stream().mapToDouble(EmployeeProfile::propensity).average().orElse(1);
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double raw = Math.pow(profiles.get(i).propensity() / meanPropensity, MULTIPLIER_EXPONENT);
            result[i] = Math.min(MAX_MULTIPLIER, Math.max(MIN_MULTIPLIER, raw));
            sum += result[i];
        }
        double mean = sum / n;
        for (int i = 0; i < n; i++) {
            result[i] /= mean;
        }
        return result;
    }

    /** 泊松抽样（Knuth），均值不大时足够快。 */
    public static int poisson(double mean, Random random) {
        if (mean <= 0) {
            return 0;
        }
        double limit = Math.exp(-mean);
        double product = 1;
        int count = 0;
        do {
            count++;
            product *= random.nextDouble();
        } while (product > limit);
        return count - 1;
    }

    /** 一次登录产生的全部浏览；没有候选内容时可能为空。 */
    public List<PlannedView> planLogin(EmployeeProfile viewer, LocalDateTime login, double multiplier, double lambda,
                                       Random random) {
        int count = poisson(lambda * multiplier, random);
        List<PlannedView> views = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            LocalDateTime time = login.plusMinutes(random.nextInt(MAX_MINUTES_AFTER_LOGIN + 1));
            LocalDateTime endOfDay = login.toLocalDate().atTime(23, 59);
            if (time.isAfter(endOfDay)) {
                time = endOfDay;
            }
            planView(viewer, time, random).ifPresent(views::add);
        }
        return views;
    }

    private Optional<PlannedView> planView(EmployeeProfile viewer, LocalDateTime time, Random random) {
        List<Candidate> window = window(time);
        if (window.isEmpty()) {
            return Optional.empty();
        }
        SourceKind kind = SourceSampler.draw(random);
        String dept = viewer.employee().getDept();
        Optional<Candidate> picked = pick(kind, viewer, dept, window, time, random);
        if (picked.isEmpty() && kind != SourceKind.RECOMMEND_ENTRY) {
            // 关注入口没有关注者、视频入口没有视频、自主入口没有候选：退回推荐入口
            kind = SourceKind.RECOMMEND_ENTRY;
            picked = pick(kind, viewer, dept, window, time, random);
        }
        if (picked.isEmpty()) {
            return Optional.empty();
        }
        Candidate content = picked.get();
        Long entryChannelId = SourceSampler.entryChannelId(kind, content.channelId(), compositeChannelIds);
        ViewDetailSampler.Dwell dwell = ViewDetailSampler.sample(content.type(), random);
        return Optional.of(new PlannedView(content.id(), viewer.employee().getId(), time, kind, entryChannelId,
            dwell.seconds(), dwell.completed()));
    }

    private Optional<Candidate> pick(SourceKind kind, EmployeeProfile viewer, String dept, List<Candidate> window,
                                     LocalDateTime time, Random random) {
        switch (kind) {
            case FOLLOW_ENTRY -> {
                Set<Long> accounts = new HashSet<>();
                for (FollowPlanner.Edge edge : edgesByFollower.getOrDefault(viewer.employee().getId(), List.of())) {
                    if (edge.activeAt(time)) {
                        accounts.add(edge.accountId());
                    }
                }
                return weightedPick(window, c -> accounts.contains(c.accountId()), time, random);
            }
            case VIDEO_ENTRY -> {
                return weightedPick(window, c -> c.type() == ContentType.VIDEO, time, random);
            }
            case OWN_ENTRY -> {
                String domain = random.nextDouble() < SAME_DOMAIN_RATIO && hasDomain(window, dept)
                    ? dept : window.get(random.nextInt(window.size())).channelName();
                return weightedPick(window, c -> c.channelName().equals(domain), time, random);
            }
            default -> {
                if (dept != null && random.nextDouble() < SAME_DOMAIN_RATIO) {
                    Optional<Candidate> inDomain = weightedPick(window, c -> dept.equals(c.channelName()), time, random);
                    if (inDomain.isPresent()) {
                        return inDomain;
                    }
                }
                return weightedPick(window, c -> true, time, random);
            }
        }
    }

    private static boolean hasDomain(List<Candidate> window, String dept) {
        if (dept == null) {
            return false;
        }
        for (Candidate candidate : window) {
            if (dept.equals(candidate.channelName())) {
                return true;
            }
        }
        return false;
    }

    /** 发布时间在 [time − 30 天, time] 内的内容（按发布时间排序的一段）。 */
    private List<Candidate> window(LocalDateTime time) {
        LocalDateTime from = time.minusDays(WINDOW_DAYS);
        int lo = firstIndexAfter(from, false);
        int hi = firstIndexAfter(time, true);
        return sorted.subList(lo, Math.max(lo, hi));
    }

    /** 二分：strictlyAfter 为 true 返回第一个发布时间晚于 time 的下标，否则返回第一个不早于 time 的下标。 */
    private int firstIndexAfter(LocalDateTime time, boolean strictlyAfter) {
        int lo = 0;
        int hi = sorted.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            LocalDateTime publish = sorted.get(mid).publishTime();
            boolean goRight = strictlyAfter ? !publish.isAfter(time) : publish.isBefore(time);
            if (goRight) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static Optional<Candidate> weightedPick(List<Candidate> window, Predicate<Candidate> filter,
                                                    LocalDateTime time, Random random) {
        double total = 0;
        for (Candidate candidate : window) {
            if (filter.test(candidate)) {
                total += weight(candidate, time);
            }
        }
        if (total <= 0) {
            return Optional.empty();
        }
        double target = random.nextDouble() * total;
        double cumulative = 0;
        Candidate last = null;
        for (Candidate candidate : window) {
            if (!filter.test(candidate)) {
                continue;
            }
            cumulative += weight(candidate, time);
            last = candidate;
            if (target < cumulative) {
                return Optional.of(candidate);
            }
        }
        return Optional.ofNullable(last);
    }

    private static double weight(Candidate candidate, LocalDateTime time) {
        double ageHours = java.time.Duration.between(candidate.publishTime(), time).toMinutes() / 60.0;
        return Math.pow(candidate.weight(), POPULARITY_EXPONENT) * Math.pow(0.5, ageHours / AGE_HALF_LIFE_HOURS);
    }
}
