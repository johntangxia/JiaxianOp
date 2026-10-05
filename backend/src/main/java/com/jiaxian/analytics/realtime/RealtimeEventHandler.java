package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.simulator.BehaviorEventGenerator;
import com.jiaxian.analytics.simulator.ViewDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在一个数据库事务里把一条实时事件交给 Plan 1 已有的积分引擎落库：
 * 一个事件产生的明细记录和两边的积分流水要么全部成功，要么全部回滚。
 */
@Service
@Transactional
public class RealtimeEventHandler {

    private final BehaviorEventGenerator eventGenerator;

    public RealtimeEventHandler(BehaviorEventGenerator eventGenerator) {
        this.eventGenerator = eventGenerator;
    }

    public void handle(RealtimeEvent event) {
        if (event.getEventType() == null) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (event.getActorEmployeeId() == null) {
            throw new IllegalArgumentException("actorEmployeeId is required");
        }

        Long actor = event.getActorEmployeeId();
        switch (event.getEventType()) {
            case VIEW -> eventGenerator.generateView(
                requireContentId(event), actor, event.getOccurTime(), event.getBatchId(),
                new ViewDetail(event.getSourceType(), event.getEntryChannelId(),
                    event.getDwellSeconds(), event.getCompleted()));
            case IMPRESSION -> eventGenerator.generateImpression(
                requireContentId(event), actor, requireEntryChannelId(event), event.getOccurTime(), false,
                event.getBatchId());
            case LIKE -> eventGenerator.generateLike(
                requireContentId(event), actor, event.getOccurTime(), event.getBatchId());
            case SHARE -> eventGenerator.generateShare(
                requireContentId(event), actor, event.getOccurTime(), event.getBatchId());
            case FAVORITE -> eventGenerator.generateFavorite(
                requireContentId(event), actor, event.getOccurTime(), event.getBatchId());
            case COMMENT -> eventGenerator.generateComment(
                requireContentId(event), event.getParentCommentId(), actor, event.getOccurTime(), event.getBatchId());
            case COMMENT_LIKE -> eventGenerator.generateCommentLike(
                requireTargetCommentId(event), actor, event.getOccurTime(), event.getBatchId());
            case LOGIN -> eventGenerator.generateLogin(actor, event.getOccurTime(), event.getBatchId());
        }
    }

    private Long requireContentId(RealtimeEvent event) {
        if (event.getContentId() == null) {
            throw new IllegalArgumentException("contentId is required for " + event.getEventType());
        }
        return event.getContentId();
    }

    private Long requireEntryChannelId(RealtimeEvent event) {
        if (event.getEntryChannelId() == null) {
            throw new IllegalArgumentException("entryChannelId is required for " + event.getEventType());
        }
        return event.getEntryChannelId();
    }

    private Long requireTargetCommentId(RealtimeEvent event) {
        if (event.getTargetCommentId() == null) {
            throw new IllegalArgumentException("targetCommentId is required for " + event.getEventType());
        }
        return event.getTargetCommentId();
    }
}
