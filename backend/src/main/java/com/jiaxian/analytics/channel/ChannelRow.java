package com.jiaxian.analytics.channel;

import java.util.List;

public record ChannelRow(long id, String name, String category, ChannelPeriod current, ChannelPeriod previous,
                         List<Diagnosis> diagnoses) {
}
