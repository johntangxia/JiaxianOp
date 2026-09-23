package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;

@Entity
@Table(name = "points_weight_config")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PointsWeightConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "action_type")
    private String actionType;

    @Enumerated(EnumType.STRING)
    private PointsCategory category;

    private Integer weight;

    @Column(name = "effective_date")
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    private LocalDate effectiveDate;
}
