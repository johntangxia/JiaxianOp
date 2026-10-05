package com.jiaxian.analytics.people;

/** 用户生命周期阶段，一个成员在某个时点只属于一个阶段。 */
public enum LifecycleStage {
    NEW("新成员"), ACTIVE("活跃"), RETURNING("回流"), LAPSING("沉默"), CHURNED("流失"), DORMANT("未激活");

    private final String label;

    LifecycleStage(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
