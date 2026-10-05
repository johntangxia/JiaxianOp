package com.jiaxian.analytics.people;

import java.time.LocalDate;
import java.util.Collection;

/**
 * 纯逻辑：生命周期阶段。窗口 W = [d−29, d]，P = [d−59, d−30]，更早 = d−59 之前。
 * 判定顺序：新成员 > 活跃 > 回流 > 沉默 > 流失 > 未激活。
 */
public final class LifecycleClassifier {

    public static final int WINDOW_DAYS = 30;

    private LifecycleClassifier() {
    }

    public static LifecycleStage classify(LocalDate joinDate, LocalDate d, boolean inWindow, boolean inPrevious, boolean earlier) {
        if (!joinDate.isBefore(d.minusDays(WINDOW_DAYS - 1))) {
            return LifecycleStage.NEW;
        }
        if (inWindow) {
            // W 有行为：P 也有 = 活跃；否则（含首次被激活）= 回流
            return inPrevious ? LifecycleStage.ACTIVE : LifecycleStage.RETURNING;
        }
        if (inPrevious) {
            return LifecycleStage.LAPSING;
        }
        return earlier ? LifecycleStage.CHURNED : LifecycleStage.DORMANT;
    }

    /** 给定员工全部活跃日（可含 d 之后的日期，会被忽略），判定 d 当天所处阶段；d 当天还没加入返回 null。 */
    public static LifecycleStage classify(LocalDate joinDate, LocalDate d, Collection<LocalDate> activeDays) {
        if (joinDate.isAfter(d)) {
            return null;
        }
        LocalDate windowStart = d.minusDays(WINDOW_DAYS - 1);
        LocalDate previousStart = d.minusDays(2L * WINDOW_DAYS - 1);
        boolean inWindow = false;
        boolean inPrevious = false;
        boolean earlier = false;
        for (LocalDate day : activeDays) {
            if (day.isAfter(d)) {
                continue;
            }
            if (!day.isBefore(windowStart)) {
                inWindow = true;
            } else if (!day.isBefore(previousStart)) {
                inPrevious = true;
            } else {
                earlier = true;
            }
        }
        return classify(joinDate, d, inWindow, inPrevious, earlier);
    }
}
