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
 * @param returnDay  淡出型沉默之后回流的日期（以"偶尔"的水平回来），不回流为 null
 */
public record EmployeeProfile(Employee employee, BloggerPersona persona, RetentionArchetype archetype,
                              boolean lurker, double propensity, LocalDate fadeStart, int fadeLength,
                              LocalDate returnDay) {

    /** 不回流的画像（旧的调用方式）。 */
    public EmployeeProfile(Employee employee, BloggerPersona persona, RetentionArchetype archetype,
                           boolean lurker, double propensity, LocalDate fadeStart, int fadeLength) {
        this(employee, persona, archetype, lurker, propensity, fadeStart, fadeLength, null);
    }

    public LocalDate joinDate() {
        return employee.getCommunityJoinDate();
    }
}
