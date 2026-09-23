package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "points_ledger")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PointsLedger {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "action_type")
    private String actionType;

    @Enumerated(EnumType.STRING)
    private PointsCategory category;

    private Integer points;

    @Column(name = "ref_id")
    private Long refId;

    @Column(name = "occur_time")
    private LocalDateTime occurTime;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
