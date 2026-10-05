package com.jiaxian.analytics.drill;

import com.jiaxian.analytics.channel.ContentMetrics;
import com.jiaxian.analytics.channel.SupplyMetrics;

/**
 * 一个对象（频道/账号/博主/内容）在一个周期里的全部指标。followers 只有账号有，其余为 null。
 * 注意：博主的指标按"作为作者"统计，合著内容每位作者都记完整的 PV 和互动。
 */
public record SubjectMetrics(ContentMetrics content, SupplyMetrics supply, ExposureMetrics exposure,
                             FollowerMetrics followers) {
}
