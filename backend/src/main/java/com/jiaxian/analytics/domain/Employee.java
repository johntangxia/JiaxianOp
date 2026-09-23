package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;

@Entity
@Table(name = "employee")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Employee {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String dept;

    @Column(name = "join_date")
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    private LocalDate joinDate;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
