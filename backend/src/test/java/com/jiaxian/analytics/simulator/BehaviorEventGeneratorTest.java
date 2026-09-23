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
            new Random(1));

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
}
