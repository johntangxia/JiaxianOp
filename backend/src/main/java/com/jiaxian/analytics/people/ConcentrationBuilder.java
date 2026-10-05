package com.jiaxian.analytics.people;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 纯逻辑：按 PV 从高到低排，算前 1 名、前 5 名、前 10%、前 20% 创作者的 PV 占比和发帖占比（至少取 1 人）。 */
public final class ConcentrationBuilder {

    private ConcentrationBuilder() {
    }

    public static List<ConcentrationShare> build(List<CreatorFigures> creators) {
        List<CreatorFigures> sorted = new ArrayList<>(creators);
        sorted.sort(Comparator.comparingLong(CreatorFigures::pv).reversed().thenComparingLong(CreatorFigures::employeeId));
        long totalPv = sorted.stream().mapToLong(CreatorFigures::pv).sum();
        long totalPosts = sorted.stream().mapToLong(CreatorFigures::posts).sum();
        int n = sorted.size();
        List<ConcentrationShare> shares = new ArrayList<>();
        shares.add(share("前 1 名", Math.min(1, n), sorted, totalPv, totalPosts));
        shares.add(share("前 5 名", Math.min(5, n), sorted, totalPv, totalPosts));
        shares.add(share("前 10%", n == 0 ? 0 : Math.max(1, (int) Math.ceil(n * 0.10)), sorted, totalPv, totalPosts));
        shares.add(share("前 20%", n == 0 ? 0 : Math.max(1, (int) Math.ceil(n * 0.20)), sorted, totalPv, totalPosts));
        return shares;
    }

    private static ConcentrationShare share(String label, int count, List<CreatorFigures> sorted, long totalPv, long totalPosts) {
        long pv = sorted.stream().limit(count).mapToLong(CreatorFigures::pv).sum();
        long posts = sorted.stream().limit(count).mapToLong(CreatorFigures::posts).sum();
        return new ConcentrationShare(label, count, totalPv == 0 ? null : pv / (double) totalPv,
            totalPosts == 0 ? null : posts / (double) totalPosts);
    }
}
