package com.jiaxian.analytics.simulator;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 在某个时刻挑一个浏览者/互动者：权重 = 活跃倾向 × 当天的活跃概率（加入社区之前为 0），
 * 所以没加入的人、已经淡出的人不会被选中。{@code preferredDept} 非空时，有 {@link #SAME_DOMAIN_RATIO} 的概率
 * 只在该领域的员工里挑；那个领域没有合格人选就退回全体。
 */
public final class ViewerPicker {

    /** 约 70% 的浏览来自内容所属领域的员工。 */
    static final double SAME_DOMAIN_RATIO = 0.7;

    private final List<EmployeeProfile> profiles;
    private final ActivityModel model;

    public ViewerPicker(List<EmployeeProfile> profiles, ActivityModel model) {
        this.profiles = profiles;
        this.model = model;
    }

    /**
     * @param interactorsOnly 只在非沉默读者里挑（用于点赞、评论等互动）
     * @return 没有任何合格人选（例如那天所有人都还没加入）时为空
     */
    public Optional<EmployeeProfile> pick(String preferredDept, boolean interactorsOnly, LocalDateTime time,
                                          Random random) {
        boolean sameDomain = preferredDept != null && random.nextDouble() < SAME_DOMAIN_RATIO;
        if (sameDomain) {
            Optional<EmployeeProfile> inDomain = drawInDept(preferredDept, interactorsOnly, time, random);
            if (inDomain.isPresent()) {
                return inDomain;
            }
        }
        return drawInDept(null, interactorsOnly, time, random);
    }

    /** 只在给定的员工 id 里挑（例如某个账号当时的关注者）；权重规则同 {@link #pick}。 */
    public Optional<EmployeeProfile> pickAmong(Set<Long> employeeIds, LocalDateTime time, Random random) {
        return draw(p -> employeeIds.contains(p.employee().getId()), false, time, random);
    }

    private Optional<EmployeeProfile> drawInDept(String dept, boolean interactorsOnly, LocalDateTime time, Random random) {
        return draw(p -> dept == null || dept.equals(p.employee().getDept()), interactorsOnly, time, random);
    }

    private Optional<EmployeeProfile> draw(Predicate<EmployeeProfile> candidate, boolean interactorsOnly,
                                           LocalDateTime time, Random random) {
        double total = 0;
        for (EmployeeProfile profile : profiles) {
            total += weight(profile, candidate, interactorsOnly, time);
        }
        if (total <= 0) {
            return Optional.empty();
        }
        double target = random.nextDouble() * total;
        double cumulative = 0;
        EmployeeProfile last = null;
        for (EmployeeProfile profile : profiles) {
            double weight = weight(profile, candidate, interactorsOnly, time);
            if (weight <= 0) {
                continue;
            }
            cumulative += weight;
            last = profile;
            if (target < cumulative) {
                return Optional.of(profile);
            }
        }
        return Optional.ofNullable(last);
    }

    private double weight(EmployeeProfile profile, Predicate<EmployeeProfile> candidate, boolean interactorsOnly,
                          LocalDateTime time) {
        if (interactorsOnly && profile.lurker()) {
            return 0;
        }
        if (!candidate.test(profile)) {
            return 0;
        }
        return profile.propensity() * model.dailyActivity(profile, time.toLocalDate());
    }
}
