package com.jiaxian.analytics.channel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 同类频道的基准中位数：只统计期间有数据的频道，没有任何频道有数据的指标不出现在结果里。 */
public final class Medians {

    public static final String OWN_ENTRY_SHARE = "OWN_ENTRY_SHARE";
    public static final String COMPOSITE_DEPENDENCY = "COMPOSITE_DEPENDENCY";
    public static final String OWN_CTR = "OWN_CTR";
    public static final String INTERACTION_RATE = "INTERACTION_RATE";
    public static final String CONTENTS = "CONTENTS";
    public static final String CTR = "CTR";
    /** 期间有内容被浏览的领域数（不是中位数，综合频道的"覆盖领域"诊断要用它）。 */
    public static final String DOMAINS_WITH_CONTENT = "DOMAINS_WITH_CONTENT";

    private Medians() {
    }

    public static Map<String, Double> domain(List<ChannelPeriod> periods) {
        Map<String, Double> result = new HashMap<>();
        put(result, OWN_ENTRY_SHARE, median(values(periods, p -> p.content().pv() > 0 ? p.content().ownEntryShare() : null)));
        put(result, COMPOSITE_DEPENDENCY,
            median(values(periods, p -> p.content().pv() > 0 ? p.content().compositeDependency() : null)));
        put(result, OWN_CTR, median(values(periods, p -> p.distribution().ctr())));
        put(result, INTERACTION_RATE, median(values(periods, p -> p.content().interactionRate())));
        put(result, CONTENTS, median(values(periods,
            p -> p.content().pv() > 0 || p.supply().contents() > 0 ? (double) p.supply().contents() : null)));
        return result;
    }

    public static Map<String, Double> composite(List<ChannelPeriod> periods, int domainsWithContent) {
        Map<String, Double> result = new HashMap<>();
        put(result, CTR, median(values(periods, p -> p.distribution().ctr())));
        result.put(DOMAINS_WITH_CONTENT, (double) domainsWithContent);
        return result;
    }

    private static List<Double> values(List<ChannelPeriod> periods, Function<ChannelPeriod, Double> extractor) {
        List<Double> values = new ArrayList<>();
        for (ChannelPeriod period : periods) {
            Double value = extractor.apply(period);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    static Double median(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private static void put(Map<String, Double> map, String key, Double value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
