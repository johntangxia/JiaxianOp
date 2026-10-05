package com.jiaxian.analytics.contest;

import java.util.List;

/**
 * 榜单里的一行。名次变化 = 上期名次 − 本期名次（正数是上升）；上期整体没有数据时，上期名次、名次变化、
 * 上期分和分数变化都为 null。最强/最弱是得分最高/最低的维度名称，最强项得分为 0 时为 null。
 */
public record ContestEntry(int rank, Integer previousRank, Integer rankChange, long channelId, String name,
                           double score, Double previousScore, Double scoreChange, List<DimensionScore> dimensions,
                           String strongest, String weakest) {
}
