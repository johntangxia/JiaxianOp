package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "content")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Content {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private ContentType type;

    @Column(name = "channel_id")
    private Long channelId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "publish_time")
    private LocalDateTime publishTime;

    @Column(name = "popularity_weight")
    private Double popularityWeight;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
