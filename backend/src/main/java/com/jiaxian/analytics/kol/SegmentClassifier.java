package com.jiaxian.analytics.kol;

/**
 * 纯逻辑：分层规则，按顺序判断，命中即停：
 * 流失预警 → 明星 KOL → 成长潜力新人 → 稳定贡献者 → 沉寂用户 → 观察中。
 *
 * <p>成长潜力新人排在稳定贡献者之前：R=5、F=2、M=2 的平均分恰好是 3.0，若先判稳定贡献者就会吞掉它。
 */
public final class SegmentClassifier {

    private SegmentClassifier() {
    }

    /**
     * @param previous    上一期的标签，没有上一期时为 null
     * @param risingTrend 近期积分是否高于上一个近期积分
     */
    public static Segment classify(RfmScores scores, boolean churnAlert, Segment previous, boolean risingTrend) {
        double average = scores.average();

        if (churnAlert && wasStarOrSteady(previous, average)) {
            return Segment.CHURN_RISK;
        }
        if (!churnAlert && average >= 4) {
            return Segment.STAR_KOL;
        }
        if (scores.r() >= 4 && scores.f() <= 2 && scores.m() <= 2 && risingTrend) {
            return Segment.RISING;
        }
        if (!churnAlert && average >= 3) {
            return Segment.STEADY;
        }
        if (scores.r() <= 2 && scores.f() <= 2 && scores.m() <= 2) {
            return Segment.DORMANT;
        }
        return Segment.OBSERVE;
    }

    /** "曾属于明星/稳定"：有上一期时看上一期标签（流失预警本身延续），没有时用当期平均分 ≥ 3 代替。 */
    private static boolean wasStarOrSteady(Segment previous, double currentAverage) {
        if (previous == null) {
            return currentAverage >= 3;
        }
        return previous == Segment.STAR_KOL || previous == Segment.STEADY || previous == Segment.CHURN_RISK;
    }
}
