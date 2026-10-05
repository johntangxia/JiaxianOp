package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.Employee;

import java.time.LocalDate;

/**
 * 员工在模拟过程中的行为画像，只存在于模拟期间，不落库——后面的沉默读者、留存分析必须从行为日志里推导，不能读这些标记。
 *
 * @param lurker     沉默读者：只浏览，不点赞/评论/转发/收藏
 * @param propensity 活跃倾向（0–1，幂律），决定被选作浏览者的相对概率
 * @param fadeStart  淡出型从这天起开始衰减（其他分型忽略）
 * @param fadeLength 淡出型从衰减起点到完全沉默的天数
 */
public record EmployeeProfile(Employee employee, BloggerPersona persona, RetentionArchetype archetype,
                              boolean lurker, double propensity, LocalDate fadeStart, int fadeLength) {

    public LocalDate joinDate() {
        return employee.getCommunityJoinDate();
    }
}
