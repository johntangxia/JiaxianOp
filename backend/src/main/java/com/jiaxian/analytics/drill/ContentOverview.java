package com.jiaxian.analytics.drill;

import com.jiaxian.analytics.channel.MixSlice;
import com.jiaxian.analytics.drill.SubjectQueryRepository.ContentInfo;
import com.jiaxian.analytics.drill.SubjectQueryRepository.EntryExposure;
import com.jiaxian.analytics.drill.SubjectQueryRepository.PersonInfo;
import com.jiaxian.analytics.pv.Granularity;

import java.time.LocalDate;
import java.util.List;

public record ContentOverview(ContentInfo content, List<PersonInfo> authors, List<String> tags, LocalDate from,
                              LocalDate to, LocalDate previousFrom, LocalDate previousTo, Granularity granularity,
                              SubjectMetrics current, SubjectMetrics previous, List<DrillTrendPoint> trend,
                              List<MixSlice> composition, List<EntryExposure> entries) {
}
