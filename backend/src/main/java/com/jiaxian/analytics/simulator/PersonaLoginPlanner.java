package com.jiaxian.analytics.simulator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 按画像规划一个员工整个历史里的登录时间（end = historyEnd，所有登录都落在 [historyStart, end) 内）：
 * STAR 每个工作日约 80% 的概率登录一次（周末乘周末因子）；CHURNING 与 STAR 相同但 end−60 天之后不再登录；
 * RISING 从加入日起每天约 12% 的概率登录（周末乘周末因子），并保证至少 2 次、最近一次落在结束前 1–3 天的随机一天
 * （最近一次要足够近：模拟里有很多几乎每天登录的常驻员工，4 天前在新近度排名里只是中游；
 * 又不能固定在同一天，否则所有 RISING 在最后一天同时登录，会让"最后一天的日活"出现尖峰）；
 * DORMANT 在 [historyStart, end−120) 内随机登录 8 次。
 * 这些常量是经验值，用来让各画像落入预期的 RFM 分层，必要时可调整。
 */
public final class PersonaLoginPlanner {

    static final double DAILY_LOGIN_PROBABILITY = 0.8;
    static final int CHURN_SILENT_DAYS = 60;
    static final int DORMANT_SILENT_DAYS = 120;
    static final int DORMANT_LOGIN_COUNT = 8;
    /** RISING 最近一次登录落在结束前 1–3 天。 */
    static final int RISING_LAST_LOGIN_MAX_DAYS_BEFORE_END = 3;

    private PersonaLoginPlanner() {
    }

    /** 没有加入日期信息的调用方式（RISING 视为从历史开始起就在）。 */
    public static List<LocalDateTime> plan(BloggerPersona persona, LocalDate historyStart, LocalDate historyEnd,
                                           Random random) {
        return plan(persona, historyStart, historyEnd, historyStart, random);
    }

    public static List<LocalDateTime> plan(BloggerPersona persona, LocalDate historyStart, LocalDate historyEnd,
                                           LocalDate joinDate, Random random) {
        List<LocalDateTime> logins = new ArrayList<>();
        switch (persona) {
            case STAR -> addDaily(logins, historyStart, historyEnd, DAILY_LOGIN_PROBABILITY, random);
            case CHURNING -> addDaily(logins, historyStart, historyEnd.minusDays(CHURN_SILENT_DAYS),
                DAILY_LOGIN_PROBABILITY, random);
            case RISING -> planRising(logins, historyStart, historyEnd, joinDate, random);
            case DORMANT -> {
                LocalDate cutoff = historyEnd.minusDays(DORMANT_SILENT_DAYS);
                long span = historyStart.until(cutoff, java.time.temporal.ChronoUnit.DAYS);
                for (int i = 0; span > 0 && i < DORMANT_LOGIN_COUNT; i++) {
                    LocalDate day = historyStart.plusDays(random.nextInt((int) span));
                    logins.add(day.atTime(9 + random.nextInt(8), random.nextInt(60)));
                }
            }
            case RANDOM -> throw new IllegalArgumentException("RANDOM employees keep the original login logic");
        }
        return logins;
    }

    private static void planRising(List<LocalDateTime> logins, LocalDate historyStart, LocalDate historyEnd,
                                   LocalDate joinDate, Random random) {
        LocalDate from = joinDate != null && joinDate.isAfter(historyStart) ? joinDate : historyStart;
        LocalDate lastDay = historyEnd.minusDays(1 + random.nextInt(RISING_LAST_LOGIN_MAX_DAYS_BEFORE_END));
        // 最后一次登录之前的日子按概率登录；"最近一次"由 lastDay 保证
        addDaily(logins, from, lastDay, ActivityModel.RISING_DAILY, random);
        if (!lastDay.isBefore(from)) {
            logins.add(lastDay.atTime(9 + random.nextInt(8), random.nextInt(60)));
        }
        // 至少 2 次：不够就在加入日和最近一次之间补一次
        if (logins.size() < 2 && lastDay.isAfter(from)) {
            LocalDate extra = from.plusDays(random.nextInt((int) from.until(lastDay, java.time.temporal.ChronoUnit.DAYS)));
            logins.add(extra.atTime(9 + random.nextInt(8), random.nextInt(60)));
        }
    }

    private static void addDaily(List<LocalDateTime> logins, LocalDate from, LocalDate toExclusive, double probability,
                                 Random random) {
        for (LocalDate day = from; day.isBefore(toExclusive); day = day.plusDays(1)) {
            if (random.nextDouble() < probability * ActivityModel.dayFactor(day)) {
                logins.add(ActivityModel.timeOfDay(day, random));
            }
        }
    }
}
