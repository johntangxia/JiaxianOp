package com.jiaxian.analytics.people;

/** 创作者排行的一行；没有 PV 时互动率为空，没有停留记录时读完率为空。 */
public record CreatorRow(long employeeId, String name, String dept, long posts, long pv, long uv, long interactions,
                         Double interactionRate, Double completionRate, long newFollowers) {
}
