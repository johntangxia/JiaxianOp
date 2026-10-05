package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "follow_relation")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FollowRelation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "follower_employee_id")
    private Long followerEmployeeId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "follow_time")
    private LocalDateTime followTime;

    /** 为空表示仍在关注。 */
    @Column(name = "unfollow_time")
    private LocalDateTime unfollowTime;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;

    /** 在 time 这个时刻是否处于关注状态。 */
    public boolean activeAt(LocalDateTime time) {
        return !followTime.isAfter(time) && (unfollowTime == null || unfollowTime.isAfter(time));
    }
}
