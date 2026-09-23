package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.*;
import com.jiaxian.analytics.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BehaviorEventGeneratorTest {

    @Mock private PvLogRepository pvLogRepository;
    @Mock private EngagementLogRepository engagementLogRepository;
    @Mock private ActiveActionLogRepository activeActionLogRepository;
    @Mock private ContentAuthorRepository contentAuthorRepository;
    @Mock private PointsWeightConfigRepository pointsWeightConfigRepository;
    @Mock private PointsLedgerRepository pointsLedgerRepository;
    @Mock private CommentRepository commentRepository;

    @Test
    void likeEventCreditsBothActorAndContentAuthorsSplitEvenly() {
        Long contentId = 100L;
        Long actorId = 1L;
        Long author1 = 2L;
        Long author2 = 3L;

        when(contentAuthorRepository.findByContentId(contentId)).thenReturn(List.of(
            ContentAuthor.builder().contentId(contentId).employeeId(author1).build(),
            ContentAuthor.builder().contentId(contentId).employeeId(author2).build()
        ));
        when(pointsWeightConfigRepository.findFirstByActionTypeOrderByEffectiveDateDesc("LIKE"))
            .thenReturn(java.util.Optional.of(PointsWeightConfig.builder()
                .actionType("LIKE").category(PointsCategory.ACTIVE).weight(3)
                .effectiveDate(LocalDate.of(2026, 1, 1)).build()));
        when(pointsWeightConfigRepository.findFirstByActionTypeOrderByEffectiveDateDesc("CONTENT_LIKED"))
            .thenReturn(java.util.Optional.of(PointsWeightConfig.builder()
                .actionType("CONTENT_LIKED").category(PointsCategory.PASSIVE).weight(3)
                .effectiveDate(LocalDate.of(2026, 1, 1)).build()));

        BehaviorEventGenerator generator = new BehaviorEventGenerator(
            pvLogRepository, engagementLogRepository, activeActionLogRepository,
            contentAuthorRepository, pointsWeightConfigRepository, pointsLedgerRepository,
            commentRepository, new Random(1));

        generator.generateLike(contentId, actorId, LocalDateTime.now(), "test-batch");

        ArgumentCaptor<PointsLedger> ledgerCaptor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsLedgerRepository, times(3)).save(ledgerCaptor.capture());

        List<PointsLedger> entries = ledgerCaptor.getAllValues();
        PointsLedger actorEntry = entries.stream()
            .filter(e -> e.getEmployeeId().equals(actorId)).findFirst().orElseThrow();
        assertThat(actorEntry.getCategory()).isEqualTo(PointsCategory.ACTIVE);
        assertThat(actorEntry.getPoints()).isEqualTo(3);

        List<PointsLedger> authorEntries = entries.stream()
            .filter(e -> !e.getEmployeeId().equals(actorId)).toList();
        assertThat(authorEntries).hasSize(2);
        assertThat(authorEntries).allMatch(e -> e.getCategory() == PointsCategory.PASSIVE);
        // 3分被动积分按2个共同作者均分，各得1（整数截断，验证均分逻辑生效）
        assertThat(authorEntries).allMatch(e -> e.getPoints() == 1);
    }

    @Test
    void postEventCreditsAuthorActivePointsOnly() {
        Long contentId = 500L;
        Long authorId = 60L;

        when(pointsWeightConfigRepository.findFirstByActionTypeOrderByEffectiveDateDesc("POST"))
            .thenReturn(java.util.Optional.of(PointsWeightConfig.builder()
                .actionType("POST").category(PointsCategory.ACTIVE).weight(100)
                .effectiveDate(LocalDate.of(2026, 1, 1)).build()));

        BehaviorEventGenerator generator = new BehaviorEventGenerator(
            pvLogRepository, engagementLogRepository, activeActionLogRepository,
            contentAuthorRepository, pointsWeightConfigRepository, pointsLedgerRepository,
            commentRepository, new Random(1));

        generator.generatePost(contentId, authorId, LocalDateTime.now(), "test-batch");

        verify(contentAuthorRepository, never()).findByContentId(any());

        ArgumentCaptor<PointsLedger> captor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsLedgerRepository, times(1)).save(captor.capture());
        PointsLedger entry = captor.getValue();
        assertThat(entry.getEmployeeId()).isEqualTo(authorId);
        assertThat(entry.getCategory()).isEqualTo(PointsCategory.ACTIVE);
        assertThat(entry.getPoints()).isEqualTo(100);
        assertThat(entry.getActionType()).isEqualTo("POST");
    }

    @Test
    void postingTopLevelCommentCreditsCommenterAndContentAuthor() {
        Long contentId = 200L;
        Long commenterId = 10L;
        Long contentAuthorId = 20L;

        when(commentRepository.save(any())).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            c.setId(999L);
            return c;
        });
        when(contentAuthorRepository.findByContentId(contentId)).thenReturn(List.of(
            ContentAuthor.builder().contentId(contentId).employeeId(contentAuthorId).build()
        ));
        when(pointsWeightConfigRepository.findFirstByActionTypeOrderByEffectiveDateDesc("COMMENT"))
            .thenReturn(java.util.Optional.of(PointsWeightConfig.builder()
                .actionType("COMMENT").category(PointsCategory.ACTIVE).weight(15)
                .effectiveDate(LocalDate.of(2026, 1, 1)).build()));
        when(pointsWeightConfigRepository.findFirstByActionTypeOrderByEffectiveDateDesc("CONTENT_COMMENTED"))
            .thenReturn(java.util.Optional.of(PointsWeightConfig.builder()
                .actionType("CONTENT_COMMENTED").category(PointsCategory.PASSIVE).weight(15)
                .effectiveDate(LocalDate.of(2026, 1, 1)).build()));

        BehaviorEventGenerator generator = new BehaviorEventGenerator(
            pvLogRepository, engagementLogRepository, activeActionLogRepository,
            contentAuthorRepository, pointsWeightConfigRepository, pointsLedgerRepository,
            commentRepository, new Random(1));

        generator.generateComment(contentId, null, commenterId, LocalDateTime.now(), "test-batch");

        ArgumentCaptor<PointsLedger> captor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsLedgerRepository, times(2)).save(captor.capture());

        List<PointsLedger> entries = captor.getAllValues();
        assertThat(entries).anyMatch(e -> e.getEmployeeId().equals(commenterId)
            && e.getCategory() == PointsCategory.ACTIVE && e.getPoints() == 15);
        assertThat(entries).anyMatch(e -> e.getEmployeeId().equals(contentAuthorId)
            && e.getCategory() == PointsCategory.PASSIVE && e.getPoints() == 15);
    }

    @Test
    void replyingToCommentCreditsReplierAndOriginalCommenterNotContentAuthor() {
        Long contentId = 200L;
        Long parentCommentId = 300L;
        Long originalCommenterId = 20L;
        Long replierId = 30L;

        Comment parentComment = Comment.builder().id(parentCommentId)
            .contentId(contentId).employeeId(originalCommenterId).build();
        when(commentRepository.findById(parentCommentId)).thenReturn(java.util.Optional.of(parentComment));
        when(commentRepository.save(any())).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            c.setId(999L);
            return c;
        });
        when(pointsWeightConfigRepository.findFirstByActionTypeOrderByEffectiveDateDesc("COMMENT"))
            .thenReturn(java.util.Optional.of(PointsWeightConfig.builder()
                .actionType("COMMENT").category(PointsCategory.ACTIVE).weight(15)
                .effectiveDate(LocalDate.of(2026, 1, 1)).build()));
        when(pointsWeightConfigRepository.findFirstByActionTypeOrderByEffectiveDateDesc("COMMENT_REPLIED"))
            .thenReturn(java.util.Optional.of(PointsWeightConfig.builder()
                .actionType("COMMENT_REPLIED").category(PointsCategory.PASSIVE).weight(15)
                .effectiveDate(LocalDate.of(2026, 1, 1)).build()));

        BehaviorEventGenerator generator = new BehaviorEventGenerator(
            pvLogRepository, engagementLogRepository, activeActionLogRepository,
            contentAuthorRepository, pointsWeightConfigRepository, pointsLedgerRepository,
            commentRepository, new Random(1));

        generator.generateComment(contentId, parentCommentId, replierId, LocalDateTime.now(), "test-batch");

        verify(contentAuthorRepository, never()).findByContentId(any());
        ArgumentCaptor<PointsLedger> captor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsLedgerRepository, times(2)).save(captor.capture());

        List<PointsLedger> entries = captor.getAllValues();
        assertThat(entries).anyMatch(e -> e.getEmployeeId().equals(replierId)
            && e.getCategory() == PointsCategory.ACTIVE && e.getPoints() == 15);
        assertThat(entries).anyMatch(e -> e.getEmployeeId().equals(originalCommenterId)
            && e.getCategory() == PointsCategory.PASSIVE && e.getPoints() == 15);
    }

    @Test
    void likingCommentCreditsLikerAndCommentAuthor() {
        Long commentId = 400L;
        Long likerId = 40L;
        Long commentAuthorId = 50L;

        Comment comment = Comment.builder().id(commentId).employeeId(commentAuthorId).build();
        when(commentRepository.findById(commentId)).thenReturn(java.util.Optional.of(comment));
        when(pointsWeightConfigRepository.findFirstByActionTypeOrderByEffectiveDateDesc("LIKE"))
            .thenReturn(java.util.Optional.of(PointsWeightConfig.builder()
                .actionType("LIKE").category(PointsCategory.ACTIVE).weight(3)
                .effectiveDate(LocalDate.of(2026, 1, 1)).build()));
        when(pointsWeightConfigRepository.findFirstByActionTypeOrderByEffectiveDateDesc("COMMENT_LIKED"))
            .thenReturn(java.util.Optional.of(PointsWeightConfig.builder()
                .actionType("COMMENT_LIKED").category(PointsCategory.PASSIVE).weight(3)
                .effectiveDate(LocalDate.of(2026, 1, 1)).build()));

        BehaviorEventGenerator generator = new BehaviorEventGenerator(
            pvLogRepository, engagementLogRepository, activeActionLogRepository,
            contentAuthorRepository, pointsWeightConfigRepository, pointsLedgerRepository,
            commentRepository, new Random(1));

        generator.generateCommentLike(commentId, likerId, LocalDateTime.now(), "test-batch");

        ArgumentCaptor<EngagementLog> engagementCaptor = ArgumentCaptor.forClass(EngagementLog.class);
        verify(engagementLogRepository).save(engagementCaptor.capture());
        assertThat(engagementCaptor.getValue().getCommentId()).isEqualTo(commentId);
        assertThat(engagementCaptor.getValue().getContentId()).isNull();

        ArgumentCaptor<PointsLedger> captor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsLedgerRepository, times(2)).save(captor.capture());

        List<PointsLedger> entries = captor.getAllValues();
        assertThat(entries).anyMatch(e -> e.getEmployeeId().equals(likerId)
            && e.getCategory() == PointsCategory.ACTIVE && e.getPoints() == 3);
        assertThat(entries).anyMatch(e -> e.getEmployeeId().equals(commentAuthorId)
            && e.getCategory() == PointsCategory.PASSIVE && e.getPoints() == 3);
    }
}
