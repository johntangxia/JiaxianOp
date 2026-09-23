package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "content_author")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ContentAuthor {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_id")
    private Long contentId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
