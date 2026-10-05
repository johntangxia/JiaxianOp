package com.jiaxian.analytics.channel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 纯逻辑：根据当期/上期指标和同类基准给出诊断。阈值是经验值；数据量不足（PV、曝光太少）时不下结论。
 * 每条诊断的说明里带具体数字和基准，运营一眼能看懂。
 */
public final class ChannelDiagnoser {

    static final long MIN_PV = 30;
    static final long MIN_IMPRESSIONS = 50;
    static final double LOW_OWN_SHARE_FACTOR = 0.5;
    static final double HIGH_DEPENDENCY_FACTOR = 1.5;
    static final double LOW_CTR_FACTOR = 0.7;
    static final double LOW_INTERACTION_FACTOR = 0.6;
    static final double HIGH_CONCENTRATION = 0.4;
    static final double LOW_SUPPLY_FACTOR = 0.5;
    static final double MIN_MEDIAN_CONTENTS = 2;
    static final double DROP_WARN = 0.8;
    static final double DROP_CRITICAL = 0.5;

    private ChannelDiagnoser() {
    }

    public static List<Diagnosis> diagnoseDomain(ChannelPeriod current, ChannelPeriod previous,
                                                 Map<String, Double> benchmark) {
        List<Diagnosis> result = new ArrayList<>();
        ContentMetrics content = current.content();
        DistributionMetrics distribution = current.distribution();

        if (content.pv() >= MIN_PV && content.sourceMix().ownEntry() == 0) {
            result.add(new Diagnosis("NO_OWN_ENTRY_TRAFFIC", Diagnosis.CRITICAL,
                "本期 " + content.pv() + " 次浏览里没有一次来自本频道自己的入口，自主分发为零"));
        } else if (content.pv() >= MIN_PV && below(content.ownEntryShare(), benchmark.get(Medians.OWN_ENTRY_SHARE),
            LOW_OWN_SHARE_FACTOR)) {
            result.add(new Diagnosis("LOW_OWN_ENTRY_SHARE", Diagnosis.WARN,
                "自主分发占比 " + pct(content.ownEntryShare()) + "，不到领域中位数 "
                    + pct(benchmark.get(Medians.OWN_ENTRY_SHARE)) + " 的一半"));
        }
        if (content.pv() >= MIN_PV && above(content.compositeDependency(), benchmark.get(Medians.COMPOSITE_DEPENDENCY),
            HIGH_DEPENDENCY_FACTOR)) {
            result.add(new Diagnosis("HIGH_COMPOSITE_DEPENDENCY", Diagnosis.WARN,
                "对综合频道的依赖度 " + pct(content.compositeDependency()) + "，高于领域中位数 "
                    + pct(benchmark.get(Medians.COMPOSITE_DEPENDENCY)) + " 的 1.5 倍"));
        }
        if (distribution.impressions() >= MIN_IMPRESSIONS
            && below(distribution.ctr(), benchmark.get(Medians.OWN_CTR), LOW_CTR_FACTOR)) {
            result.add(new Diagnosis("LOW_OWN_CTR", Diagnosis.WARN,
                "自主入口点击率 " + pct(distribution.ctr()) + "，低于领域中位数 "
                    + pct(benchmark.get(Medians.OWN_CTR)) + " 的 70%"));
        }
        if (content.pv() >= MIN_PV
            && below(content.interactionRate(), benchmark.get(Medians.INTERACTION_RATE), LOW_INTERACTION_FACTOR)) {
            result.add(new Diagnosis("LOW_INTERACTION", Diagnosis.WARN,
                "互动率 " + pct(content.interactionRate()) + "，低于领域中位数 "
                    + pct(benchmark.get(Medians.INTERACTION_RATE)) + " 的 60%"));
        }
        if (content.pv() >= MIN_PV && content.top1Share() != null && content.top1Share() > HIGH_CONCENTRATION) {
            result.add(new Diagnosis("HIGH_CONCENTRATION", Diagnosis.INFO,
                "单篇内容占了本频道 " + pct(content.top1Share()) + " 的浏览，流量过于集中"));
        }
        Double medianContents = benchmark.get(Medians.CONTENTS);
        if (medianContents != null && medianContents >= MIN_MEDIAN_CONTENTS
            && current.supply().contents() < medianContents * LOW_SUPPLY_FACTOR) {
            result.add(new Diagnosis("LOW_SUPPLY", Diagnosis.WARN,
                "本期只发布了 " + current.supply().contents() + " 篇内容，领域中位数是 "
                    + trimmed(medianContents) + " 篇"));
        }
        if (previous != null && previous.content().pv() >= MIN_PV) {
            double ratio = content.pv() / (double) previous.content().pv();
            if (ratio < DROP_WARN) {
                result.add(new Diagnosis("PV_DROP", ratio < DROP_CRITICAL ? Diagnosis.CRITICAL : Diagnosis.WARN,
                    "PV 比上一周期下降 " + pct(1 - ratio) + "（" + previous.content().pv() + " → " + content.pv() + "）"));
            }
        }
        return result;
    }

    public static List<Diagnosis> diagnoseComposite(ChannelPeriod current, ChannelPeriod previous,
                                                    Map<String, Double> benchmark) {
        List<Diagnosis> result = new ArrayList<>();
        DistributionMetrics distribution = current.distribution();

        if (distribution.impressions() >= MIN_IMPRESSIONS
            && below(distribution.ctr(), benchmark.get(Medians.CTR), LOW_CTR_FACTOR)) {
            result.add(new Diagnosis("LOW_CTR", Diagnosis.WARN,
                "点击率 " + pct(distribution.ctr()) + "，低于综合频道中位数 " + pct(benchmark.get(Medians.CTR)) + " 的 70%"));
        }
        if (previous != null && previous.distribution().clicks() >= MIN_PV) {
            double ratio = distribution.clicks() / (double) previous.distribution().clicks();
            if (ratio < DROP_WARN) {
                result.add(new Diagnosis("CLICK_DROP", ratio < DROP_CRITICAL ? Diagnosis.CRITICAL : Diagnosis.WARN,
                    "点击比上一周期下降 " + pct(1 - ratio) + "（" + previous.distribution().clicks() + " → "
                        + distribution.clicks() + "）"));
            }
        }
        Double domainsWithContent = benchmark.get(Medians.DOMAINS_WITH_CONTENT);
        if (distribution.clicks() >= MIN_PV && domainsWithContent != null && domainsWithContent > 0
            && current.coverage().domainsCovered() < domainsWithContent / 2) {
            result.add(new Diagnosis("LOW_COVERAGE", Diagnosis.INFO,
                "只分发了 " + current.coverage().domainsCovered() + " 个领域的内容，有内容的领域共 "
                    + trimmed(domainsWithContent) + " 个"));
        }
        return result;
    }

    private static boolean below(Double value, Double median, double factor) {
        return value != null && median != null && value < median * factor;
    }

    private static boolean above(Double value, Double median, double factor) {
        return value != null && median != null && value > median * factor;
    }

    private static String pct(Double ratio) {
        return String.format("%.1f%%", ratio * 100);
    }

    private static String trimmed(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format("%.1f", value);
    }
}
