package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "impression")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Impression {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "viewer_employee_id")
    private Long viewerEmployeeId;

    @Column(name = "content_id")
    private Long contentId;

    @Column(name = "entry_channel_id")
    private Long entryChannelId;

    @Column(name = "impression_time")
    private LocalDateTime impressionTime;

    private boolean clicked;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
