package com.jiaxian.analytics.channel;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 频道驾驶舱：综合频道和领域频道各一组行（每行含当期、上期和诊断）以及各自的基准中位数。
 * 基准里没有数据的指标不出现在 map 里。
 */
public record Cockpit(LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo,
                      List<ChannelRow> composite, List<ChannelRow> domain,
                      Map<String, Double> compositeBenchmark, Map<String, Double> domainBenchmark) {
}
