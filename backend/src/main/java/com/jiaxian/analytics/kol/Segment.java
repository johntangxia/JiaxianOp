package com.jiaxian.analytics.kol;

/** 博主分层标签。 */
public enum Segment {
    STAR_KOL("明星KOL"),
    STEADY("稳定贡献者"),
    RISING("成长潜力新人"),
    CHURN_RISK("流失预警"),
    DORMANT("沉寂用户"),
    OBSERVE("观察中");

    private final String displayName;

    Segment(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
