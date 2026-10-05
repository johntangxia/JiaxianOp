package com.jiaxian.analytics.growth;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "growth_task")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GrowthTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @Column(name = "task_type")
    @Enumerated(EnumType.STRING)
    private TaskType taskType;

    @Column(name = "entity_type")
    @Enumerated(EnumType.STRING)
    private GrowthEntityType entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "entity_name")
    private String entityName;

    /** 跟踪的指标，见 {@link TaskMetric}。 */
    private String metric;

    /** 创建时算好的基线值：开始日前一天往前 30 天的指标值；当时没有数据为空。 */
    @Column(name = "baseline_value")
    private Double baselineValue;

    @Column(name = "target_value")
    private Double targetValue;

    /** 负责人，自由文本（没有账号体系）。 */
    @Column(name = "owner_name")
    private String ownerName;

    @Column(name = "start_date")
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    private LocalDate startDate;

    @Column(name = "due_date")
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    private TaskStatus status;

    private String note;

    @Column(name = "alert_id")
    private Long alertId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
