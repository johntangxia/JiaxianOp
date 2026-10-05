package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "pv_log")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PvLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_id")
    private Long contentId;

    @Column(name = "viewer_employee_id")
    private Long viewerEmployeeId;

    @Column(name = "view_time")
    private LocalDateTime viewTime;

    /** 浏览来自哪个频道的入口（仅 ENTRY 来源有值）；等于内容所属频道即"自主入口"，是综合频道即"综合入口"。 */
    @Column(name = "entry_channel_id")
    private Long entryChannelId;

    /** 旧数据为空。 */
    @Column(name = "source_type")
    @Enumerated(EnumType.STRING)
    private ViewSource sourceType;

    @Column(name = "dwell_seconds")
    private Integer dwellSeconds;

    /** 文章是否读完、视频是否播完。 */
    private Boolean completed;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
