package com.jiaxian.analytics.channel;

import com.jiaxian.analytics.channel.ChannelQueryRepository.TopContent;
import com.jiaxian.analytics.pv.Granularity;

import java.util.List;

public record ChannelDetail(ChannelRow row, Granularity granularity, List<TrendPoint> trend,
                            List<MixSlice> composition, List<TopContent> topContents) {
}
