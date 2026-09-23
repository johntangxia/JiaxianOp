package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "editorial_pick_log")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EditorialPickLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_id")
    private Long contentId;

    @Column(name = "pick_time")
    private LocalDateTime pickTime;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
