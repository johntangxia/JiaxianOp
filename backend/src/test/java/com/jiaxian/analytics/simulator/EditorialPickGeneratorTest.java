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
class EditorialPickGeneratorTest {

    @Mock private ContentAuthorRepository contentAuthorRepository;
    @Mock private EditorialPickLogRepository editorialPickLogRepository;
    @Mock private PointsWeightConfigRepository pointsWeightConfigRepository;
    @Mock private PointsLedgerRepository pointsLedgerRepository;

    @Test
    void picksTopContentByPopularityAndCreditsAuthors() {
        Content top = Content.builder().id(1L).popularityWeight(0.95).build();
        Content mid = Content.builder().id(2L).popularityWeight(0.5).build();
        Content low = Content.builder().id(3L).popularityWeight(0.1).build();

        when(contentAuthorRepository.findByContentId(1L)).thenReturn(List.of(
            ContentAuthor.builder().contentId(1L).employeeId(100L).build()));
        when(pointsWeightConfigRepository.findFirstByActionTypeOrderByEffectiveDateDesc("EDITORIAL_PICK"))
            .thenReturn(java.util.Optional.of(PointsWeightConfig.builder()
                .actionType("EDITORIAL_PICK").category(PointsCategory.PASSIVE).weight(300)
                .effectiveDate(LocalDate.of(2026, 1, 1)).build()));

        EditorialPickGenerator generator = new EditorialPickGenerator(
            contentAuthorRepository, editorialPickLogRepository,
            pointsWeightConfigRepository, pointsLedgerRepository, new Random(1));

        generator.pickTopContent(List.of(top, mid, low), 1, LocalDateTime.now(), "test-batch");

        ArgumentCaptor<EditorialPickLog> pickCaptor = ArgumentCaptor.forClass(EditorialPickLog.class);
        verify(editorialPickLogRepository).save(pickCaptor.capture());
        assertThat(pickCaptor.getValue().getContentId()).isEqualTo(1L);

        ArgumentCaptor<PointsLedger> ledgerCaptor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsLedgerRepository).save(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getEmployeeId()).isEqualTo(100L);
        assertThat(ledgerCaptor.getValue().getPoints()).isEqualTo(300);
    }
}
