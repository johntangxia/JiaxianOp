package com.jiaxian.analytics.simulator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 按画像规划一个员工整个历史里的登录时间（end = historyEnd，所有登录都落在 [historyStart, end) 内）：
 * STAR 每天约 80% 的概率登录一次；CHURNING 与 STAR 相同但 end−60 天之后不再登录；
 * RISING 只在 end−20、end−4 各登录一次；DORMANT 在 [historyStart, end−120) 内随机登录 8 次。
 * 这些常量是经验值，用来让各画像落入预期的 RFM 分层，必要时可调整。
 */
public final class PersonaLoginPlanner {

    static final double DAILY_LOGIN_PROBABILITY = 0.8;
    static final int CHURN_SILENT_DAYS = 60;
    static final int DORMANT_SILENT_DAYS = 120;
    static final int DORMANT_LOGIN_COUNT = 8;
    static final int[] RISING_DAYS_BEFORE_END = {20, 4};

    private PersonaLoginPlanner() {
    }

    public static List<LocalDateTime> plan(BloggerPersona persona, LocalDate historyStart, LocalDate historyEnd,
                                           Random random) {
        List<LocalDateTime> logins = new ArrayList<>();
        switch (persona) {
            case STAR -> addDaily(logins, historyStart, historyEnd, random);
            case CHURNING -> addDaily(logins, historyStart, historyEnd.minusDays(CHURN_SILENT_DAYS), random);
            case RISING -> {
                for (int daysBeforeEnd : RISING_DAYS_BEFORE_END) {
                    LocalDate day = historyEnd.minusDays(daysBeforeEnd);
                    if (!day.isBefore(historyStart)) {
                        logins.add(day.atTime(10, 0));
                    }
                }
            }
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

    private static void addDaily(List<LocalDateTime> logins, LocalDate from, LocalDate toExclusive, Random random) {
        for (LocalDate day = from; day.isBefore(toExclusive); day = day.plusDays(1)) {
            if (random.nextDouble() < DAILY_LOGIN_PROBABILITY) {
                logins.add(day.atTime(9 + random.nextInt(8), random.nextInt(60)));
            }
        }
    }
}
