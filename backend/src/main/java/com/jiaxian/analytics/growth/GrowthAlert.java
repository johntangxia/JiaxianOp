package com.jiaxian.analytics.growth;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "growth_alert")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GrowthAlert {
    public static final String CRITICAL = "CRITICAL";
    public static final String WARN = "WARN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String kind;

    /** CRITICAL 或 WARN。 */
    private String severity;

    @Column(name = "entity_type")
    @Enumerated(EnumType.STRING)
    private GrowthEntityType entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "entity_name")
    private String entityName;

    @Column(name = "period_end")
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    private LocalDate periodEnd;

    private String message;

    @Enumerated(EnumType.STRING)
    private AlertStatus status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
