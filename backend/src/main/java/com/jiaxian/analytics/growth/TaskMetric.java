package com.jiaxian.analytics.growth;

import java.util.EnumSet;
import java.util.Set;

/** 任务可以跟踪的指标及它适用的对象类型。所有指标都是"越大越好"。 */
public enum TaskMetric {
    PV(EnumSet.of(GrowthEntityType.CHANNEL, GrowthEntityType.ACCOUNT, GrowthEntityType.BLOGGER)),
    OWN_ENTRY_SHARE(EnumSet.of(GrowthEntityType.CHANNEL)),
    OWN_CTR(EnumSet.of(GrowthEntityType.CHANNEL)),
    INTERACTION_RATE(EnumSet.of(GrowthEntityType.CHANNEL, GrowthEntityType.ACCOUNT, GrowthEntityType.BLOGGER)),
    POSTS(EnumSet.of(GrowthEntityType.CHANNEL, GrowthEntityType.ACCOUNT, GrowthEntityType.BLOGGER)),
    PENETRATION(EnumSet.of(GrowthEntityType.DEPARTMENT)),
    ACTIVE_USERS(EnumSet.of(GrowthEntityType.DEPARTMENT, GrowthEntityType.OVERALL)),
    ACTIVE_RATE(EnumSet.of(GrowthEntityType.OVERALL));

    private final Set<GrowthEntityType> entityTypes;

    TaskMetric(Set<GrowthEntityType> entityTypes) {
        this.entityTypes = entityTypes;
    }

    public boolean appliesTo(GrowthEntityType type) {
        return entityTypes.contains(type);
    }
}
