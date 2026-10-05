package com.jiaxian.analytics.channel;

import com.jiaxian.analytics.channel.ChannelQueryRepository.ContentSide;

import java.util.List;

/** 由聚合结果算出内容侧指标；频道驾驶舱和下钻（账号、博主、内容）共用，保证口径一致。 */
public final class ContentMetricsFactory {

    private ContentMetricsFactory() {
    }

    /**
     * @param side        PV、UV 和来源拆分；没有浏览时为 null
     * @param interactions 期间内的互动数（点赞、转发、收藏加评论）
     * @param perContentPv 每篇内容的期间 PV，从高到低，用来算头部集中度；可以为空
     */
    public static ContentMetrics build(ContentSide side, long interactions, List<Long> perContentPv) {
        if (side == null || side.pv() == 0) {
            return new ContentMetrics(0, 0, interactions, null, null, null, SourceMix.EMPTY, null, null);
        }
        double pv = side.pv();
        long top3 = perContentPv.stream().limit(3).mapToLong(Long::longValue).sum();
        return new ContentMetrics(side.pv(), side.uv(), interactions, interactions / pv,
            perContentPv.isEmpty() ? null : perContentPv.get(0) / pv, perContentPv.isEmpty() ? null : top3 / pv,
            side.mix(), side.mix().ownEntry() / pv, side.mix().compositeEntry() / pv);
    }
}
