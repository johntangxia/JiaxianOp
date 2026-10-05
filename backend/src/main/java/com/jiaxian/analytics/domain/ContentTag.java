package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "content_tag")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ContentTag {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_id")
    private Long contentId;

    private String tag;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
