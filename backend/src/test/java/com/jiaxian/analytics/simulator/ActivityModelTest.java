package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.Employee;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityModelTest {

    private static final LocalDate END = LocalDate.of(2026, 3, 1);
    private static final LocalDate MONDAY = LocalDate.of(2025, 6, 2);
    private static final LocalDate SATURDAY = LocalDate.of(2025, 6, 7);
    private final ActivityModel model = new ActivityModel(END);

    private static EmployeeProfile profile(BloggerPersona persona, RetentionArchetype archetype, LocalDate join) {
        return new EmployeeProfile(Employee.builder().id(1L).communityJoinDate(join).build(), persona, archetype,
            false, 1.0, LocalDate.of(2025, 7, 1), 60);
    }

    @Test
    void noActivityBeforeJoiningTheCommunity() {
        EmployeeProfile p = profile(BloggerPersona.RANDOM, RetentionArchetype.REGULAR, LocalDate.of(2025, 6, 10));

        assertThat(model.dailyActivity(p, LocalDate.of(2025, 6, 9))).isZero();
        assertThat(model.dailyActivity(p, LocalDate.of(2025, 6, 10))).isPositive();
    }

    @Test
    void weekendIsThirtyFivePercentOfAWeekday() {
        EmployeeProfile p = profile(BloggerPersona.RANDOM, RetentionArchetype.REGULAR, null);

        assertThat(model.dailyActivity(p, MONDAY)).isEqualTo(ActivityModel.REGULAR_DAILY);
        assertThat(model.dailyActivity(p, SATURDAY)).isEqualTo(ActivityModel.REGULAR_DAILY * 0.35);
    }

    @Test
    void regularIsMoreActiveThanOccasional() {
        EmployeeProfile regular = profile(BloggerPersona.RANDOM, RetentionArchetype.REGULAR, null);
        EmployeeProfile occasional = profile(BloggerPersona.RANDOM, RetentionArchetype.OCCASIONAL, null);

        assertThat(model.dailyActivity(regular, MONDAY)).isGreaterThan(model.dailyActivity(occasional, MONDAY) * 5);
    }

    @Test
    void fadingStartsAtFullActivityThenDecaysToZero() {
        // fadeStart = 2025-07-01，fadeLength = 60 天
        EmployeeProfile p = profile(BloggerPersona.RANDOM, RetentionArchetype.FADING, null);
        LocalDate beforeFade = LocalDate.of(2025, 6, 30);        // 周一
        LocalDate midFade = LocalDate.of(2025, 7, 31);           // 周四，衰减了 30 天
        LocalDate afterFade = LocalDate.of(2025, 9, 1);          // 周一，衰减结束之后

        assertThat(model.dailyActivity(p, beforeFade)).isEqualTo(ActivityModel.REGULAR_DAILY);
        assertThat(model.dailyActivity(p, midFade)).isEqualTo(ActivityModel.REGULAR_DAILY * 0.5);
        assertThat(model.dailyActivity(p, afterFade)).isZero();
    }

    @Test
    void personasFollowTheirLoginPlans() {
        LocalDate recentMonday = LocalDate.of(2026, 2, 23);   // 距离 END 6 天
        LocalDate midMonday = LocalDate.of(2025, 12, 1);      // 距离 END 90 天
        LocalDate oldMonday = LocalDate.of(2025, 6, 2);       // 距离 END 272 天

        assertThat(model.dailyActivity(profile(BloggerPersona.STAR, RetentionArchetype.REGULAR, null), recentMonday))
            .isEqualTo(ActivityModel.STAR_DAILY);
        EmployeeProfile churning = profile(BloggerPersona.CHURNING, RetentionArchetype.REGULAR, null);
        assertThat(model.dailyActivity(churning, midMonday)).isEqualTo(ActivityModel.STAR_DAILY);
        assertThat(model.dailyActivity(churning, recentMonday)).isZero();
        EmployeeProfile dormant = profile(BloggerPersona.DORMANT, RetentionArchetype.REGULAR, null);
        assertThat(model.dailyActivity(dormant, oldMonday)).isEqualTo(ActivityModel.DORMANT_DAILY);
        assertThat(model.dailyActivity(dormant, midMonday)).isZero();
        assertThat(model.dailyActivity(profile(BloggerPersona.RISING, RetentionArchetype.REGULAR, null), recentMonday))
            .isEqualTo(ActivityModel.RISING_DAILY);
    }

    @Test
    void timeOfDayConcentratesInWorkingHours() {
        Random random = new Random(1);
        int[] perHour = new int[24];
        for (int i = 0; i < 20_000; i++) {
            perHour[ActivityModel.timeOfDay(MONDAY, random).getHour()]++;
        }

        int working = Arrays.stream(perHour, 9, 12).sum() + Arrays.stream(perHour, 14, 18).sum();
        int night = Arrays.stream(perHour, 0, 6).sum();
        assertThat(working).isGreaterThan(20_000 * 55 / 100);
        assertThat(night).isLessThan(20_000 * 3 / 100);
    }

    @Test
    void viewTimesDecayWithATwoDayHalfLifeAndStayWithinThirtyDays() {
        Random random = new Random(2);
        LocalDateTime publish = MONDAY.atTime(10, 0);
        int n = 20_000;
        int withinTwoDays = 0;
        int withinFourDays = 0;
        for (int i = 0; i < n; i++) {
            LocalDateTime t = ActivityModel.viewTime(publish, random);
            long hours = ChronoUnit.HOURS.between(publish, t);
            assertThat(hours).isBetween(0L, 24L * 30);
            if (hours < 48) withinTwoDays++;
            if (hours < 96) withinFourDays++;
        }
        // 半衰期 2 天：前两天约占 50%，前四天约占 75%（日历拒绝采样会让数字略有偏移，给足余量）
        assertThat(withinTwoDays / (double) n).isBetween(0.40, 0.62);
        assertThat(withinFourDays / (double) n).isBetween(0.65, 0.88);
    }

    @Test
    void weekendsReceiveFewerViewsPerDayThanWeekdays() {
        Random random = new Random(3);
        long weekend = 0;
        long weekday = 0;
        for (int i = 0; i < 30_000; i++) {
            LocalDate day = ActivityModel.viewTime(MONDAY.atTime(10, 0), random).toLocalDate();
            switch (day.getDayOfWeek()) {
                case SATURDAY, SUNDAY -> weekend++;
                default -> weekday++;
            }
        }
        // 每天平均：周末两天 / 工作日五天
        assertThat(weekend / 2.0).isLessThan(weekday / 5.0 * 0.6);
    }

    @Test
    void publishTimesFallInRangeAndFavourWeekdays() {
        Random random = new Random(4);
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 7, 1);
        long weekend = 0;
        for (int i = 0; i < 5_000; i++) {
            LocalDateTime t = ActivityModel.publishTime(from, to, random);
            assertThat(t.toLocalDate()).isBetween(from, to.minusDays(1));
            if (ActivityModel.dayFactor(t.toLocalDate()) < 1) weekend++;
        }
        // 均匀分布下周末占 2/7≈28.6%，拒绝采样后应明显更低
        assertThat(weekend / 5_000.0).isLessThan(0.20);
    }

    @Test
    void sameSeedGivesSameSamples() {
        LocalDateTime a = ActivityModel.viewTime(MONDAY.atTime(9, 0), new Random(9));
        LocalDateTime b = ActivityModel.viewTime(MONDAY.atTime(9, 0), new Random(9));
        assertThat(a).isEqualTo(b);
    }
}
