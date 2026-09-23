package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "account_member")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccountMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
