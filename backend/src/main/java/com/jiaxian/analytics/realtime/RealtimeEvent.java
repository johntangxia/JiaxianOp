package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.domain.ViewSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RealtimeEvent {

    private RealtimeEventType eventType;
    /** 除 LOGIN 外都需要 */
    private Long contentId;
    /** 仅 COMMENT 事件、且是"回复"而不是"新建顶层评论"时才非空 */
    private Long parentCommentId;
    /** 仅 COMMENT_LIKE 事件需要，表示被点赞的评论 id */
    private Long targetCommentId;
    /** 仅 VIEW / IMPRESSION：浏览来源、入口频道、停留时长、是否读完。旧的发送方不带这些字段，为空。 */
    private ViewSource sourceType;
    private Long entryChannelId;
    private Integer dwellSeconds;
    private Boolean completed;
    private Long actorEmployeeId;
    private LocalDateTime occurTime;
    private String batchId;

    /** Kafka 消息 key：LOGIN 不关联内容，用员工 id；其余用内容 id，保证同一篇内容的事件落在同一分区、有序。 */
    public String partitionKey() {
        if (eventType == RealtimeEventType.LOGIN) {
            return String.valueOf(actorEmployeeId);
        }
        return String.valueOf(contentId);
    }
}
