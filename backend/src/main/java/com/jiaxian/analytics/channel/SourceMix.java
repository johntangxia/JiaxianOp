package com.jiaxian.analytics.channel;

/** 一个领域频道的内容的 PV 按来源拆分：自主入口、综合入口、搜索、分享、其他（含旧数据里来源为空的浏览）。 */
public record SourceMix(long ownEntry, long compositeEntry, long search, long share, long other) {

    public static final SourceMix EMPTY = new SourceMix(0, 0, 0, 0, 0);

    public long total() {
        return ownEntry + compositeEntry + search + share + other;
    }
}
