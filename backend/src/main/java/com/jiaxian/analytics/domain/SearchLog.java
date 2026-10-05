package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "search_log")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SearchLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id")
    private Long employeeId;

    private String keyword;

    @Column(name = "search_time")
    private LocalDateTime searchTime;

    /** 搜索后点开的内容；没点击为空。 */
    @Column(name = "clicked_content_id")
    private Long clickedContentId;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
