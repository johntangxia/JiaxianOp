package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.ViewSource;

/**
 * 一次浏览的"来源细类"，比 {@link ViewSource} 多区分了入口：自主入口（领域频道自己的入口）和四个综合入口。
 * {@code share} 是该来源占全部 PV 的比例（业务方给出的现状：分享 40%、综合入口 15%、搜索 10%、自主入口 2%，
 * 余下 33% 归 OTHER）；{@code ctr} 是入口的点击率（经验值，只有入口来源才有曝光）。
 */
public enum SourceKind {
    SHARE(ViewSource.SHARE, 0.40, null),
    SEARCH(ViewSource.SEARCH, 0.10, null),
    OTHER(ViewSource.OTHER, 0.33, null),
    /** 内容所属领域频道自己的入口。 */
    OWN_ENTRY(ViewSource.ENTRY, 0.02, 0.12),
    RECOMMEND_ENTRY(ViewSource.ENTRY, 0.08, 0.04),
    HOT_ENTRY(ViewSource.ENTRY, 0.03, 0.06),
    /** 视频入口只分发视频类内容。 */
    VIDEO_ENTRY(ViewSource.ENTRY, 0.02, 0.10),
    /** 关注入口只分发浏览者已关注账号的内容。 */
    FOLLOW_ENTRY(ViewSource.ENTRY, 0.02, 0.20);

    private final ViewSource viewSource;
    private final double share;
    private final Double ctr;

    SourceKind(ViewSource viewSource, double share, Double ctr) {
        this.viewSource = viewSource;
        this.share = share;
        this.ctr = ctr;
    }

    public ViewSource viewSource() {
        return viewSource;
    }

    public double share() {
        return share;
    }

    public boolean isEntry() {
        return viewSource == ViewSource.ENTRY;
    }

    /** 入口点击率；非入口来源没有曝光，调用会抛异常。 */
    public double ctr() {
        if (ctr == null) {
            throw new IllegalStateException(this + " has no impressions");
        }
        return ctr;
    }

    /** 综合入口对应的综合频道名；自主入口和非入口返回 null。 */
    public String compositeChannelName() {
        return switch (this) {
            case RECOMMEND_ENTRY -> "推荐";
            case HOT_ENTRY -> "热点";
            case VIDEO_ENTRY -> "视频";
            case FOLLOW_ENTRY -> "关注";
            default -> null;
        };
    }
}
