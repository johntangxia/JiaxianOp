package com.jiaxian.analytics.growth;

import com.jiaxian.analytics.channel.ChannelRow;
import com.jiaxian.analytics.channel.Diagnosis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 纯逻辑：告警规则，把各处算好的数字变成告警候选。阈值是常量。 */
public final class AlertRules {

    static final double DAU_DROP_WARN = 0.20;
    static final double DAU_DROP_CRITICAL = 0.50;
    static final double MIN_PREVIOUS_DAU = 5;
    static final long MIN_JOINERS = 10;
    static final double MIN_ACTIVATION = 0.30;
    static final double LOW_PENETRATION_FACTOR = 0.5;
    static final long MIN_DEPT_MEMBERS = 20;
    static final int MAX_CHURN_ALERTS = 30;

    private AlertRules() {
    }

    /** 频道驾驶舱诊断里的严重和警告级别（提示级别不告警）；kind = CHANNEL_ + 诊断代码，说明沿用诊断原文。 */
    public static List<AlertCandidate> channelDiagnoses(List<ChannelRow> rows) {
        List<AlertCandidate> alerts = new ArrayList<>();
        for (ChannelRow row : rows) {
            for (Diagnosis d : row.diagnoses()) {
                if (Diagnosis.INFO.equals(d.severity())) {
                    continue;
                }
                alerts.add(new AlertCandidate("CHANNEL_" + d.code(), d.severity(), GrowthEntityType.CHANNEL, row.id(),
                    row.name(), d.message()));
            }
        }
        return alerts;
    }

    /** 近 7 天日均 DAU 比前 7 天下降超过 20%（前 7 天日均 ≥ 5）；超过 50% 为严重。 */
    public static Optional<AlertCandidate> dauDrop(double averageDau, double previousAverageDau) {
        if (previousAverageDau < MIN_PREVIOUS_DAU) {
            return Optional.empty();
        }
        double drop = 1 - averageDau / previousAverageDau;
        if (drop <= DAU_DROP_WARN) {
            return Optional.empty();
        }
        return Optional.of(new AlertCandidate("OVERALL_DAU_DROP", drop > DAU_DROP_CRITICAL ? GrowthAlert.CRITICAL : GrowthAlert.WARN,
            GrowthEntityType.OVERALL, null, "全社区", String.format("近 7 天日均 DAU %.1f，比前 7 天的 %.1f 下降 %.1f%%",
                averageDau, previousAverageDau, drop * 100)));
    }

    /** 新成员第 2 周仍活跃的比例低于 30%（至少 10 个新成员）。 */
    public static Optional<AlertCandidate> lowActivation(long joiners, long retained) {
        if (joiners < MIN_JOINERS) {
            return Optional.empty();
        }
        double rate = retained / (double) joiners;
        if (rate >= MIN_ACTIVATION) {
            return Optional.empty();
        }
        return Optional.of(new AlertCandidate("OVERALL_LOW_ACTIVATION", GrowthAlert.WARN, GrowthEntityType.OVERALL, null,
            "全社区", String.format("最近加入的 %d 位新成员里，第 2 周仍活跃的只有 %d 位（%.1f%%）", joiners, retained, rate * 100)));
    }

    /** 渗透率低于各部门中位数的一半，且成员 ≥ 20。 */
    public static List<AlertCandidate> lowPenetration(List<DepartmentRow> departments) {
        List<Double> values = departments.stream().map(d -> d.current().penetration()).filter(v -> v != null).sorted().toList();
        if (values.isEmpty()) {
            return List.of();
        }
        int n = values.size();
        double median = n % 2 == 1 ? values.get(n / 2) : (values.get(n / 2 - 1) + values.get(n / 2)) / 2;
        List<AlertCandidate> alerts = new ArrayList<>();
        for (DepartmentRow row : departments) {
            Double penetration = row.current().penetration();
            if (penetration != null && row.current().members() >= MIN_DEPT_MEMBERS && penetration < median * LOW_PENETRATION_FACTOR) {
                alerts.add(new AlertCandidate("DEPARTMENT_LOW_PENETRATION", GrowthAlert.WARN, GrowthEntityType.DEPARTMENT, null,
                    row.dept(), String.format("%s 的渗透率 %.1f%%，不到各部门中位数 %.1f%% 的一半（成员 %d 人）", row.dept(),
                        penetration * 100, median * 100, row.current().members())));
            }
        }
        return alerts;
    }

    /** 流失预警的博主，最多取排名最靠前的 30 个。 */
    public static List<AlertCandidate> bloggerChurn(List<ChurnRisk> risks) {
        return risks.stream().sorted(Comparator.comparingInt(ChurnRisk::rank)).limit(MAX_CHURN_ALERTS)
            .map(r -> new AlertCandidate("BLOGGER_CHURN_RISK", GrowthAlert.WARN, GrowthEntityType.BLOGGER, r.employeeId(),
                r.name(), "KOL 名单第 " + r.rank() + " 位的博主近期明显沉默（流失预警）"))
            .toList();
    }
}
