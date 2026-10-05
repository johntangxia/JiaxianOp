package com.jiaxian.analytics.drill;

import com.jiaxian.analytics.channel.ChannelQueryRepository.TopContent;
import com.jiaxian.analytics.channel.MixSlice;
import com.jiaxian.analytics.drill.SubjectQueryRepository.BloggerInfo;
import com.jiaxian.analytics.pv.Granularity;

import java.time.LocalDate;
import java.util.List;

/** 博主（作为作者）的下钻。kolProfile 表示他在 KOL 名单里，可以进 KOL 详情页。 */
public record BloggerOverview(BloggerInfo blogger, LocalDate from, LocalDate to, LocalDate previousFrom,
                              LocalDate previousTo, Granularity granularity, SubjectMetrics current,
                              SubjectMetrics previous, List<DrillTrendPoint> trend, List<MixSlice> composition,
                              List<BreakdownRow> accounts, List<BreakdownRow> channels, List<TopContent> topContents) {
}
