package com.jiaxian.analytics.people;

import com.jiaxian.analytics.people.PeopleQueryRepository.Event;
import com.jiaxian.analytics.people.PeopleQueryRepository.FollowedAccount;
import com.jiaxian.analytics.people.PeopleQueryRepository.WeekActivity;

import java.time.LocalDate;
import java.util.List;

/** 用户 360：一个员工的完整画像。hasPosts 为 true 时可以进博主下钻页，kol 不为空时可以进 KOL 详情。 */
public record UserProfile(LocalDate asOf, Basic basic, Recent recent, List<WeekActivity> timeline, Reading reading,
                          List<FollowedAccount> follows, Points points, List<Event> recentEvents, boolean hasPosts, Kol kol) {

    public record Basic(long employeeId, String name, String dept, LocalDate joinDate, Long daysInCommunity,
                        LifecycleStage stage, String stageLabel, LifecycleStage previousStage, String previousStageLabel,
                        ValueSegment valueSegment, String valueLabel, CreatorStage creatorStage, String creatorStageLabel) {
    }

    /** 近 30 天；role 同读者分析的读者角色。 */
    public record Recent(long activeDays, LocalDate lastActive, long views, long interactions, long posts, String role,
                         String roleLabel) {
    }

    public record Reading(long views, List<Share> channels, List<Share> tags, List<Share> sources, Double videoShare,
                          Double avgDwellSeconds, Double completionRate) {
    }

    /** share = count / 浏览总数。 */
    public record Share(String label, long count, double share) {
    }

    /** 近 12 个月的积分合计。 */
    public record Points(long active, long passive, long total) {
    }

    public record Kol(LocalDate asOf, int rank, String segment, String segmentName) {
    }
}
