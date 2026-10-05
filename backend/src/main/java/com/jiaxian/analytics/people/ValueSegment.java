package com.jiaxian.analytics.people;

/** 用户价值分层（RFM），判定顺序固定。 */
public enum ValueSegment {
    CORE("核心"), ACTIVE("活跃"), TO_WAKE("待唤醒"), LOW_ACTIVE("低活跃"), SLEEPING("沉睡");

    private final String label;

    ValueSegment(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
