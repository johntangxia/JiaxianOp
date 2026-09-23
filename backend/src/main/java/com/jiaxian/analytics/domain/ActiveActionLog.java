package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "active_action_log")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ActiveActionLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id")
    private Long employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type")
    private ActiveActionType actionType;

    @Column(name = "ref_id")
    private Long refId;

    @Column(name = "occur_time")
    private LocalDateTime occurTime;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
