package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "engagement_log")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EngagementLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_id")
    private Long contentId;

    @Column(name = "comment_id")
    private Long commentId;

    @Column(name = "actor_employee_id")
    private Long actorEmployeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type")
    private EngagementActionType actionType;

    @Column(name = "occur_time")
    private LocalDateTime occurTime;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
