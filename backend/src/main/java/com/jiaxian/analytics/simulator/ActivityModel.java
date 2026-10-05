package com.jiaxian.analytics.simulator;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Random;

/**
 * 谁在什么时候活跃：把"每日活跃概率、周末因子、一天内的小时分布、浏览热度衰减"集中在一处。
 * 全部是确定性的纯函数（随机数由调用方传入），常量是经验值，用来让模拟数据有可分析的规律，必要时可调整。
 */
public final class ActivityModel {

    static final double WEEKEND_FACTOR = 0.35;
    static final double REGULAR_DAILY = 0.7;
    static final double OCCASIONAL_DAILY = 0.05;
    static final double STAR_DAILY = 0.8;
    static final double RISING_DAILY = 0.12;
    static final double DORMANT_DAILY = 0.03;
    /** 浏览热度的半衰期（小时）：发布后每过两天，单位时间内的浏览量减半。 */
    static final double VIEW_HALF_LIFE_HOURS = 48;
    static final int VIEW_WINDOW_DAYS = 30;
    private static final int MAX_REJECTION_TRIES = 10;

    /** 一天 24 个小时的相对活跃度：上午 9–11、下午 14–17 是高峰，午休和晚上低，凌晨极低。 */
    static final double[] HOUR_WEIGHTS = {
        0.02, 0.02, 0.02, 0.02, 0.02, 0.02, 0.10, 0.30, 0.60, 1.00, 1.00, 0.80,
        0.35, 0.60, 0.95, 1.00, 0.95, 0.60, 0.30, 0.25, 0.30, 0.25, 0.12, 0.05};
    private static final double MAX_HOUR_WEIGHT = 1.0;
    private static final double HOUR_WEIGHT_SUM;

    static {
        double sum = 0;
        for (double w : HOUR_WEIGHTS) {
            sum += w;
        }
        HOUR_WEIGHT_SUM = sum;
    }

    private final LocalDate historyEnd;

    public ActivityModel(LocalDate historyEnd) {
        this.historyEnd = historyEnd;
    }

    public static double dayFactor(LocalDate day) {
        DayOfWeek dow = day.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY ? WEEKEND_FACTOR : 1.0;
    }

    /** 这个员工在这一天有行为（登录/浏览）的概率；加入社区之前为 0，周末乘周末因子。 */
    public double dailyActivity(EmployeeProfile profile, LocalDate day) {
        LocalDate join = profile.joinDate();
        if (join != null && day.isBefore(join)) {
            return 0;
        }
        double base = switch (profile.persona()) {
            case STAR -> STAR_DAILY;
            case CHURNING -> day.isBefore(historyEnd.minusDays(PersonaLoginPlanner.CHURN_SILENT_DAYS)) ? STAR_DAILY : 0;
            case RISING -> RISING_DAILY;
            case DORMANT -> day.isBefore(historyEnd.minusDays(PersonaLoginPlanner.DORMANT_SILENT_DAYS)) ? DORMANT_DAILY : 0;
            case RANDOM -> switch (profile.archetype()) {
                case REGULAR -> REGULAR_DAILY;
                case OCCASIONAL -> OCCASIONAL_DAILY;
                case FADING -> fadingActivity(profile, day);
            };
        };
        return base * dayFactor(day);
    }

    private static double fadingActivity(EmployeeProfile profile, LocalDate day) {
        if (!day.isAfter(profile.fadeStart())) {
            return REGULAR_DAILY;
        }
        double elapsed = profile.fadeStart().until(day, java.time.temporal.ChronoUnit.DAYS);
        if (elapsed >= profile.fadeLength() && profile.returnDay() != null && !day.isBefore(profile.returnDay())) {
            return OCCASIONAL_DAILY;   // 沉默一阵子之后回流，但只是偶尔来
        }
        return REGULAR_DAILY * Math.max(0, 1 - elapsed / profile.fadeLength());
    }

    /** 按小时权重抽一个一天内的时刻。 */
    public static LocalDateTime timeOfDay(LocalDate day, Random random) {
        double target = random.nextDouble() * HOUR_WEIGHT_SUM;
        double cumulative = 0;
        int hour = HOUR_WEIGHTS.length - 1;
        for (int h = 0; h < HOUR_WEIGHTS.length; h++) {
            cumulative += HOUR_WEIGHTS[h];
            if (target < cumulative) {
                hour = h;
                break;
            }
        }
        return day.atTime(hour, random.nextInt(60));
    }

    /** [from, toExclusive) 内的一天，周末被接受的概率只有工作日的 35%；区间为空时返回 from。 */
    public static LocalDate workingDay(LocalDate from, LocalDate toExclusive, Random random) {
        int span = (int) Math.max(1, from.until(toExclusive, java.time.temporal.ChronoUnit.DAYS));
        LocalDate candidate = from;
        for (int i = 0; i < MAX_REJECTION_TRIES; i++) {
            candidate = from.plusDays(random.nextInt(span));
            if (random.nextDouble() < dayFactor(candidate)) {
                break;
            }
        }
        return candidate;
    }

    /** 内容发布时刻：[from, toExclusive) 内偏工作日、偏上班时段。 */
    public static LocalDateTime publishTime(LocalDate from, LocalDate toExclusive, Random random) {
        return timeOfDay(workingDay(from, toExclusive, random), random);
    }

    /**
     * 一次浏览发生的时刻：发布后经过半衰期约 2 天的指数衰减（截断在 30 天内），
     * 再按周末和小时权重做拒绝采样，最多重试 10 次，之后接受最后一个候选值。
     */
    public static LocalDateTime viewTime(LocalDateTime publishTime, Random random) {
        double lambda = Math.log(2) / VIEW_HALF_LIFE_HOURS;
        double windowHours = 24.0 * VIEW_WINDOW_DAYS;
        double tail = 1 - Math.exp(-lambda * windowHours);
        LocalDateTime candidate = publishTime;
        for (int i = 0; i < MAX_REJECTION_TRIES; i++) {
            double offsetHours = -Math.log(1 - random.nextDouble() * tail) / lambda;
            candidate = publishTime.plusMinutes((long) (offsetHours * 60));
            double accept = dayFactor(candidate.toLocalDate()) * HOUR_WEIGHTS[candidate.getHour()] / MAX_HOUR_WEIGHT;
            if (random.nextDouble() < accept) {
                break;
            }
        }
        return candidate;
    }
}
