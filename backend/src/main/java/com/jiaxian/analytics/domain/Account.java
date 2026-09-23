package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "account")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Enumerated(EnumType.STRING)
    private AccountType type;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
