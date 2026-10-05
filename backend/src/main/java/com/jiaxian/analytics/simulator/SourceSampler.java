package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.ContentType;

import java.util.Map;
import java.util.Random;

/** 抽样一次浏览的来源，并处理"视频入口只给视频、关注入口只给有关注者的内容"这两条约束。 */
public final class SourceSampler {

    private SourceSampler() {
    }

    /** 按 {@link SourceKind#share()} 的占比抽一个来源细类。 */
    public static SourceKind draw(Random random) {
        double target = random.nextDouble();
        double cumulative = 0;
        for (SourceKind kind : SourceKind.values()) {
            cumulative += kind.share();
            if (target < cumulative) {
                return kind;
            }
        }
        return SourceKind.OTHER;
    }

    /** 视频入口遇到文章、关注入口没有关注者时，退回推荐入口；其余原样返回。 */
    public static SourceKind applyConstraints(SourceKind kind, ContentType contentType, boolean hasFollowers) {
        if (kind == SourceKind.VIDEO_ENTRY && contentType != ContentType.VIDEO) {
            return SourceKind.RECOMMEND_ENTRY;
        }
        if (kind == SourceKind.FOLLOW_ENTRY && !hasFollowers) {
            return SourceKind.RECOMMEND_ENTRY;
        }
        return kind;
    }

    /**
     * 入口来源的入口频道 id：自主入口 = 内容所属频道，综合入口 = 对应的综合频道；非入口来源为 null。
     *
     * @param compositeChannelIds 综合频道名（推荐/热点/视频/关注）到频道 id
     */
    public static Long entryChannelId(SourceKind kind, long contentChannelId, Map<String, Long> compositeChannelIds) {
        if (!kind.isEntry()) {
            return null;
        }
        if (kind == SourceKind.OWN_ENTRY) {
            return contentChannelId;
        }
        Long id = compositeChannelIds.get(kind.compositeChannelName());
        if (id == null) {
            throw new IllegalStateException("Composite channel not found: " + kind.compositeChannelName());
        }
        return id;
    }
}
