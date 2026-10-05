package com.jiaxian.analytics.drill;

import com.jiaxian.analytics.channel.ChannelQueryRepository.TopContent;
import com.jiaxian.analytics.channel.MixSlice;
import com.jiaxian.analytics.drill.SubjectQueryRepository.AccountInfo;
import com.jiaxian.analytics.pv.Granularity;

import java.time.LocalDate;
import java.util.List;

public record AccountOverview(AccountInfo account, LocalDate from, LocalDate to, LocalDate previousFrom,
                              LocalDate previousTo, Granularity granularity, SubjectMetrics current,
                              SubjectMetrics previous, List<DrillTrendPoint> trend, List<MixSlice> composition,
                              List<BreakdownRow> authors, List<BreakdownRow> channels, List<TopContent> topContents) {
}
