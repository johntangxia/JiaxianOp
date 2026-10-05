package com.jiaxian.analytics.contest;

import com.jiaxian.analytics.pv.Granularity;

/** 竞赛周期：周榜（周一至周日）、月榜（自然月）。 */
public enum ContestType {
    WEEK(Granularity.WEEK), MONTH(Granularity.MONTH);

    private final Granularity granularity;

    ContestType(Granularity granularity) {
        this.granularity = granularity;
    }

    public Granularity granularity() {
        return granularity;
    }
}
