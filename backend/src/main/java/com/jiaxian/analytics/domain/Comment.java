package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "comment")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Comment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_id")
    private Long contentId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "parent_comment_id")
    private Long parentCommentId;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
