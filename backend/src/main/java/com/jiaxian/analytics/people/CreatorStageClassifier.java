package com.jiaxian.analytics.people;

/** 纯逻辑：成长阶梯阶段。KOL > 活跃创作者（近 90 天发帖 ≥ 3）> 创作者（发过帖）> 互动者 > 读者（有行为）> 未激活。 */
public final class CreatorStageClassifier {

    static final long ACTIVE_CREATOR_POSTS = 3;

    private CreatorStageClassifier() {
    }

    public static CreatorStage classify(LifetimeFacts facts, boolean kol) {
        if (kol) {
            return CreatorStage.KOL;
        }
        if (facts == null || facts.firstActivity() == null) {
            return CreatorStage.INACTIVE;
        }
        if (facts.firstPost() != null) {
            return facts.recentPosts() >= ACTIVE_CREATOR_POSTS ? CreatorStage.ACTIVE_CREATOR : CreatorStage.CREATOR;
        }
        return facts.firstInteraction() != null ? CreatorStage.INTERACTOR : CreatorStage.READER;
    }
}
