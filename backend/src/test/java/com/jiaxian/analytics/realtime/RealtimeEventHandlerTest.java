package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.simulator.BehaviorEventGenerator;
import com.jiaxian.analytics.simulator.ViewDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class RealtimeEventHandlerTest {

    private static final Long ACTOR = 7L;
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 4, 12, 0);
    private static final String BATCH = "handler-test";

    @Mock private BehaviorEventGenerator eventGenerator;

    private RealtimeEventHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RealtimeEventHandler(eventGenerator);
    }

    private RealtimeEvent.RealtimeEventBuilder event(RealtimeEventType type) {
        return RealtimeEvent.builder().eventType(type).actorEmployeeId(ACTOR).occurTime(TIME).batchId(BATCH);
    }

    @Test
    void viewDispatchesToGenerateView() {
        handler.handle(event(RealtimeEventType.VIEW).contentId(10L).build());

        verify(eventGenerator).generateView(10L, ACTOR, TIME, BATCH, ViewDetail.NONE);
        verifyNoMoreInteractions(eventGenerator);
    }

    @Test
    void likeDispatchesToGenerateLike() {
        handler.handle(event(RealtimeEventType.LIKE).contentId(10L).build());

        verify(eventGenerator).generateLike(10L, ACTOR, TIME, BATCH);
        verifyNoMoreInteractions(eventGenerator);
    }

    @Test
    void shareDispatchesToGenerateShare() {
        handler.handle(event(RealtimeEventType.SHARE).contentId(10L).build());

        verify(eventGenerator).generateShare(10L, ACTOR, TIME, BATCH);
        verifyNoMoreInteractions(eventGenerator);
    }

    @Test
    void favoriteDispatchesToGenerateFavorite() {
        handler.handle(event(RealtimeEventType.FAVORITE).contentId(10L).build());

        verify(eventGenerator).generateFavorite(10L, ACTOR, TIME, BATCH);
        verifyNoMoreInteractions(eventGenerator);
    }

    @Test
    void topLevelCommentDispatchesWithNullParent() {
        handler.handle(event(RealtimeEventType.COMMENT).contentId(10L).build());

        verify(eventGenerator).generateComment(10L, null, ACTOR, TIME, BATCH);
        verifyNoMoreInteractions(eventGenerator);
    }

    @Test
    void replyCommentDispatchesWithParentCommentId() {
        handler.handle(event(RealtimeEventType.COMMENT).contentId(10L).parentCommentId(55L).build());

        verify(eventGenerator).generateComment(10L, 55L, ACTOR, TIME, BATCH);
        verifyNoMoreInteractions(eventGenerator);
    }

    @Test
    void commentLikeDispatchesToGenerateCommentLike() {
        handler.handle(event(RealtimeEventType.COMMENT_LIKE).contentId(10L).targetCommentId(55L).build());

        verify(eventGenerator).generateCommentLike(55L, ACTOR, TIME, BATCH);
        verifyNoMoreInteractions(eventGenerator);
    }

    @Test
    void loginDispatchesToGenerateLogin() {
        handler.handle(event(RealtimeEventType.LOGIN).build());

        verify(eventGenerator).generateLogin(ACTOR, TIME, BATCH);
        verifyNoMoreInteractions(eventGenerator);
    }

    @Test
    void missingEventTypeIsRejected() {
        assertThatThrownBy(() -> handler.handle(event(null).contentId(10L).build()))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(eventGenerator);
    }

    @Test
    void missingActorIsRejected() {
        RealtimeEvent event = event(RealtimeEventType.VIEW).contentId(10L).actorEmployeeId(null).build();

        assertThatThrownBy(() -> handler.handle(event)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(eventGenerator);
    }

    @Test
    void contentEventWithoutContentIdIsRejected() {
        assertThatThrownBy(() -> handler.handle(event(RealtimeEventType.VIEW).build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("contentId");
        verifyNoInteractions(eventGenerator);
    }

    @Test
    void commentLikeWithoutTargetCommentIdIsRejected() {
        assertThatThrownBy(() -> handler.handle(event(RealtimeEventType.COMMENT_LIKE).contentId(10L).build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("targetCommentId");
        verifyNoInteractions(eventGenerator);
    }

    @Test
    void viewWithSourceDetailPassesItThrough() {
        handler.handle(event(RealtimeEventType.VIEW).contentId(10L).sourceType(com.jiaxian.analytics.domain.ViewSource.ENTRY)
            .entryChannelId(7L).dwellSeconds(42).completed(true).build());

        verify(eventGenerator).generateView(10L, ACTOR, TIME, BATCH,
            new ViewDetail(com.jiaxian.analytics.domain.ViewSource.ENTRY, 7L, 42, true));
        verifyNoMoreInteractions(eventGenerator);
    }

    @Test
    void impressionDispatchesToGenerateUnclickedImpression() {
        handler.handle(event(RealtimeEventType.IMPRESSION).contentId(10L).entryChannelId(7L).build());

        verify(eventGenerator).generateImpression(10L, ACTOR, 7L, TIME, false, BATCH);
        verifyNoMoreInteractions(eventGenerator);
    }

    @Test
    void impressionWithoutEntryChannelIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> handler.handle(event(RealtimeEventType.IMPRESSION).contentId(10L).build()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("entryChannelId");
    }
}
