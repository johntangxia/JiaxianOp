package com.jiaxian.analytics.growth;

/** 纯逻辑：任务进度 = (当前值 − 基线) / (目标值 − 基线)；任何一个值缺失或目标等于基线时为 null；可以超过 100% 或为负。 */
public final class TaskProgress {

    private TaskProgress() {
    }

    public static Double progress(Double baseline, Double target, Double current) {
        if (baseline == null || target == null || current == null || target.equals(baseline)) {
            return null;
        }
        return (current - baseline) / (target - baseline);
    }
}
