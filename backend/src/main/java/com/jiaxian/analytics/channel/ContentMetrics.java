package com.jiaxian.analytics.channel;

/**
 * 领域频道的内容侧指标：内容属于该领域，不论从哪里被看到。
 *
 * @param interactionRate     互动数 / PV，没有 PV 时为 null
 * @param top1Share           PV 最高的一篇占比
 * @param top3Share           PV 最高的三篇占比
 * @param ownEntryShare       自主分发占比 = 自主入口 PV / PV
 * @param compositeDependency 综合依赖度 = 综合入口 PV / PV
 */
public record ContentMetrics(long pv, long uv, long interactions, Double interactionRate, Double top1Share,
                             Double top3Share, SourceMix sourceMix, Double ownEntryShare,
                             Double compositeDependency) {

    public static final ContentMetrics EMPTY =
        new ContentMetrics(0, 0, 0, null, null, null, SourceMix.EMPTY, null, null);
}
