package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.*;
import com.jiaxian.analytics.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 来源、入口、曝光、关注、搜索、标签这些新增的落库行为。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BehaviorEventGeneratorBehaviorDataTest {

    private static final LocalDateTime T = LocalDateTime.of(2019, 6, 3, 10, 0);

    @Mock private PvLogRepository pvLogRepository;
    @Mock private EngagementLogRepository engagementLogRepository;
    @Mock private ActiveActionLogRepository activeActionLogRepository;
    @Mock private ContentAuthorRepository contentAuthorRepository;
    @Mock private PointsWeightConfigRepository pointsWeightConfigRepository;
    @Mock private PointsLedgerRepository pointsLedgerRepository;
    @Mock private CommentRepository commentRepository;
    @Mock private ImpressionRepository impressionRepository;
    @Mock private FollowRelationRepository followRelationRepository;
    @Mock private SearchLogRepository searchLogRepository;
    @Mock private ContentTagRepository contentTagRepository;

    private BehaviorEventGenerator generator;

    @BeforeEach
    void setUp() {
        for (String action : List.of("VIEW", "CONTENT_VIEWED")) {
            when(pointsWeightConfigRepository.findFirstByActionTypeOrderByEffectiveDateDesc(action))
                .thenReturn(Optional.of(PointsWeightConfig.builder().actionType(action).category(PointsCategory.ACTIVE)
                    .weight(1).effectiveDate(LocalDate.of(2026, 1, 1)).build()));
        }
        generator = new BehaviorEventGenerator(pvLogRepository, engagementLogRepository, activeActionLogRepository,
            contentAuthorRepository, pointsWeightConfigRepository, pointsLedgerRepository, commentRepository,
            impressionRepository, followRelationRepository, searchLogRepository, contentTagRepository, new Random(1));
    }

    @Test
    void entryViewStoresSourceAndDwellAndAClickedImpression() {
        generator.generateView(10L, 1L, T, "b", new ViewDetail(ViewSource.ENTRY, 99L, 75, true));

        ArgumentCaptor<PvLog> pv = ArgumentCaptor.forClass(PvLog.class);
        verify(pvLogRepository).save(pv.capture());
        assertThat(pv.getValue().getSourceType()).isEqualTo(ViewSource.ENTRY);
        assertThat(pv.getValue().getEntryChannelId()).isEqualTo(99L);
        assertThat(pv.getValue().getDwellSeconds()).isEqualTo(75);
        assertThat(pv.getValue().getCompleted()).isTrue();

        ArgumentCaptor<Impression> impression = ArgumentCaptor.forClass(Impression.class);
        verify(impressionRepository).save(impression.capture());
        assertThat(impression.getValue().isClicked()).isTrue();
        assertThat(impression.getValue().getEntryChannelId()).isEqualTo(99L);
        assertThat(impression.getValue().getContentId()).isEqualTo(10L);
        assertThat(impression.getValue().getViewerEmployeeId()).isEqualTo(1L);
        assertThat(impression.getValue().getImpressionTime()).isEqualTo(T);
    }

    @Test
    void nonEntryViewsDoNotCreateImpressions() {
        generator.generateView(10L, 1L, T, "b", new ViewDetail(ViewSource.SHARE, null, 30, false));
        generator.generateView(10L, 1L, T, "b", new ViewDetail(ViewSource.SEARCH, null, 30, false));
        generator.generateView(10L, 1L, T, "b");

        verify(pvLogRepository, times(3)).save(any());
        verify(impressionRepository, never()).save(any());
    }

    @Test
    void viewStillCreditsViewerActivePointsAndAuthorPassivePoints() {
        when(contentAuthorRepository.findByContentId(10L)).thenReturn(List.of(
            ContentAuthor.builder().contentId(10L).employeeId(2L).build()));

        generator.generateView(10L, 1L, T, "b", new ViewDetail(ViewSource.OTHER, null, 20, false));

        verify(pointsLedgerRepository, times(2)).save(any());
    }

    @Test
    void followSearchImpressionAndTagsArePersistedWithTheBatch() {
        generator.generateFollow(1L, 5L, T, T.plusDays(40), "b");
        generator.generateSearch(1L, "半导体", T, 10L, "b");
        generator.generateSearch(1L, "光", T, null, "b");
        generator.generateImpression(10L, 2L, 99L, T, false, "b");
        generator.generateTags(10L, List.of("半导体", "测试"), "b");

        ArgumentCaptor<FollowRelation> follow = ArgumentCaptor.forClass(FollowRelation.class);
        verify(followRelationRepository).save(follow.capture());
        assertThat(follow.getValue().getAccountId()).isEqualTo(5L);
        assertThat(follow.getValue().getUnfollowTime()).isEqualTo(T.plusDays(40));

        ArgumentCaptor<SearchLog> search = ArgumentCaptor.forClass(SearchLog.class);
        verify(searchLogRepository, times(2)).save(search.capture());
        assertThat(search.getAllValues()).extracting(SearchLog::getClickedContentId).containsExactly(10L, null);

        ArgumentCaptor<Impression> impression = ArgumentCaptor.forClass(Impression.class);
        verify(impressionRepository).save(impression.capture());
        assertThat(impression.getValue().isClicked()).isFalse();

        ArgumentCaptor<ContentTag> tags = ArgumentCaptor.forClass(ContentTag.class);
        verify(contentTagRepository, times(2)).save(tags.capture());
        assertThat(tags.getAllValues()).extracting(ContentTag::getTag).containsExactly("半导体", "测试");
        assertThat(tags.getAllValues()).allMatch(t -> "b".equals(t.getSimulationBatchId()));
    }

    @Test
    void followingDoesNotAwardAnyPoints() {
        generator.generateFollow(1L, 5L, T, null, "b");

        verifyNoInteractions(pointsLedgerRepository, activeActionLogRepository);
    }

    @Test
    void referenceDataCachingAvoidsRepeatedWeightAndAuthorLookups() {
        when(contentAuthorRepository.findByContentId(10L)).thenReturn(List.of(ContentAuthor.builder().contentId(10L).employeeId(2L).build()));
        generator.cacheReferenceData();

        for (int i = 0; i < 5; i++) {
            generator.generateView(10L, 1L, T, "b");
        }

        verify(pointsWeightConfigRepository, times(1)).findFirstByActionTypeOrderByEffectiveDateDesc("VIEW");
        verify(pointsWeightConfigRepository, times(1)).findFirstByActionTypeOrderByEffectiveDateDesc("CONTENT_VIEWED");
        verify(contentAuthorRepository, times(1)).findByContentId(10L);
        verify(pointsLedgerRepository, times(10)).save(any());   // 缓存不影响写入
    }

    @Test
    void withoutCachingEveryEventLooksTheWeightUpAgainSoRuntimeChangesApply() {
        for (int i = 0; i < 3; i++) {
            generator.generateView(10L, 1L, T, "b");
        }

        verify(pointsWeightConfigRepository, times(3)).findFirstByActionTypeOrderByEffectiveDateDesc("VIEW");
    }
}
