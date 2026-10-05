package com.jiaxian.analytics.reader;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Predicate;

/**
 * 纯逻辑：新成员激活漏斗。各步相对加入日 0 点：7 天内首次浏览、7 天内首次互动、30 天内首次发帖、
 * 加入后第 2 周（第 7–14 天）仍活跃。各步独立统计（一个人可以没浏览就发帖），所以只给"占加入人数的比例"，不给相对上一步的转化率。
 */
public final class FunnelBuilder {

    static final int VIEW_DAYS = 7;
    static final int INTERACTION_DAYS = 7;
    static final int POST_DAYS = 30;

    private FunnelBuilder() {
    }

    public static List<FunnelStep> build(List<FunnelFacts> facts) {
        long joined = facts.size();
        return List.of(
            step("JOINED", "加入社区", joined, joined),
            step("VIEWED", "7 天内首次浏览", count(facts, f -> within(f, f.firstView(), VIEW_DAYS)), joined),
            step("INTERACTED", "7 天内首次互动", count(facts, f -> within(f, f.firstInteraction(), INTERACTION_DAYS)), joined),
            step("POSTED", "30 天内首次发帖", count(facts, f -> within(f, f.firstPost(), POST_DAYS)), joined),
            step("RETAINED", "第 2 周仍活跃", count(facts, FunnelFacts::activeInSecondWeek), joined));
    }

    private static boolean within(FunnelFacts facts, LocalDateTime time, int days) {
        return time != null && time.isBefore(facts.joinDate().atStartOfDay().plusDays(days));
    }

    private static long count(List<FunnelFacts> facts, Predicate<FunnelFacts> predicate) {
        return facts.stream().filter(predicate).count();
    }

    private static FunnelStep step(String key, String label, long count, long joined) {
        return new FunnelStep(key, label, count, joined == 0 ? null : count / (double) joined);
    }
}
