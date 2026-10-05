package com.jiaxian.analytics.people;

/** 成长阶梯上的阶段：判定时取最高的一个。 */
public enum CreatorStage {
    INACTIVE("未激活"), READER("读者"), INTERACTOR("互动者"), CREATOR("创作者"), ACTIVE_CREATOR("活跃创作者"), KOL("KOL");

    private final String label;

    CreatorStage(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
