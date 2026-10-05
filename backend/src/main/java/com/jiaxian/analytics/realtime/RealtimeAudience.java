package com.jiaxian.analytics.realtime;

import java.util.Map;

/**
 * 实时模拟的"受众"：谁更活跃、内容属于哪个领域。
 *
 * @param actorWeights 员工 id → 被选作演员的相对权重（取自最近 90 天的登录次数，所以常驻的人多、淡出的人几乎不出现）；
 *                     没有任何权重时退回在已加入的员工里均匀挑
 * @param channelNames 频道 id → 频道名（领域频道名 = 部门名，用来做"约 70% 看本领域"的偏好）
 */
public record RealtimeAudience(Map<Long, Double> actorWeights, Map<Long, String> channelNames) {
}
