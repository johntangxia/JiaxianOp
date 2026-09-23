package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "pv_log")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PvLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_id")
    private Long contentId;

    @Column(name = "viewer_employee_id")
    private Long viewerEmployeeId;

    @Column(name = "view_time")
    private LocalDateTime viewTime;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
