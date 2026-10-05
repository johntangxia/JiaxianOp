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

    /** 首次进入社区的日期（不是公司入职日）；旧数据可能为空，空值视为一直在社区里。 */
    @Column(name = "community_join_date")
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    private LocalDate communityJoinDate;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
