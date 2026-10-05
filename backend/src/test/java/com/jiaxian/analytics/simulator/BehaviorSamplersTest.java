package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.ContentType;
import com.jiaxian.analytics.domain.ViewSource;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BehaviorSamplersTest {

    private static final Map<String, Long> COMPOSITE = Map.of("推荐", 2L, "热点", 3L, "视频", 4L, "关注", 1L);

    @Test
    void sourceSharesSumToOneAndMatchTheBusinessNumbers() {
        double total = 0;
        double entryComposite = 0;
        for (SourceKind kind : SourceKind.values()) {
            total += kind.share();
            if (kind.compositeChannelName() != null) {
                entryComposite += kind.share();
            }
        }
        assertThat(total).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(entryComposite).isCloseTo(0.15, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(SourceKind.SHARE.share()).isEqualTo(0.40);
        assertThat(SourceKind.SEARCH.share()).isEqualTo(0.10);
        assertThat(SourceKind.OWN_ENTRY.share()).isEqualTo(0.02);
        assertThat(SourceKind.OTHER.share()).isEqualTo(0.33);
    }

    @Test
    void drawnSourcesFollowTheConfiguredShares() {
        Random random = new Random(1);
        Map<SourceKind, Integer> counts = new EnumMap<>(SourceKind.class);
        int n = 100_000;
        for (int i = 0; i < n; i++) {
            counts.merge(SourceSampler.draw(random), 1, Integer::sum);
        }
        for (SourceKind kind : SourceKind.values()) {
            assertThat(counts.getOrDefault(kind, 0) / (double) n).as(kind.name())
                .isCloseTo(kind.share(), org.assertj.core.data.Offset.offset(0.01));
        }
    }

    @Test
    void onlyEntryKindsHaveAClickThroughRate() {
        assertThat(SourceKind.FOLLOW_ENTRY.ctr()).isGreaterThan(SourceKind.OWN_ENTRY.ctr());
        assertThat(SourceKind.OWN_ENTRY.ctr()).isGreaterThan(SourceKind.HOT_ENTRY.ctr());
        assertThat(SourceKind.HOT_ENTRY.ctr()).isGreaterThan(SourceKind.RECOMMEND_ENTRY.ctr());
        assertThatThrownBy(SourceKind.SEARCH::ctr).isInstanceOf(IllegalStateException.class);
        assertThat(SourceKind.OWN_ENTRY.viewSource()).isEqualTo(ViewSource.ENTRY);
        assertThat(SourceKind.SHARE.isEntry()).isFalse();
    }

    @Test
    void videoEntryFallsBackToRecommendForArticlesAndFollowEntryWithoutFollowers() {
        assertThat(SourceSampler.applyConstraints(SourceKind.VIDEO_ENTRY, ContentType.ARTICLE, true))
            .isEqualTo(SourceKind.RECOMMEND_ENTRY);
        assertThat(SourceSampler.applyConstraints(SourceKind.VIDEO_ENTRY, ContentType.VIDEO, true))
            .isEqualTo(SourceKind.VIDEO_ENTRY);
        assertThat(SourceSampler.applyConstraints(SourceKind.FOLLOW_ENTRY, ContentType.ARTICLE, false))
            .isEqualTo(SourceKind.RECOMMEND_ENTRY);
        assertThat(SourceSampler.applyConstraints(SourceKind.FOLLOW_ENTRY, ContentType.ARTICLE, true))
            .isEqualTo(SourceKind.FOLLOW_ENTRY);
        assertThat(SourceSampler.applyConstraints(SourceKind.SHARE, ContentType.ARTICLE, false))
            .isEqualTo(SourceKind.SHARE);
    }

    @Test
    void entryChannelIsTheContentsOwnChannelForOwnEntryAndACompositeChannelOtherwise() {
        assertThat(SourceSampler.entryChannelId(SourceKind.OWN_ENTRY, 77L, COMPOSITE)).isEqualTo(77L);
        assertThat(SourceSampler.entryChannelId(SourceKind.RECOMMEND_ENTRY, 77L, COMPOSITE)).isEqualTo(2L);
        assertThat(SourceSampler.entryChannelId(SourceKind.HOT_ENTRY, 77L, COMPOSITE)).isEqualTo(3L);
        assertThat(SourceSampler.entryChannelId(SourceKind.VIDEO_ENTRY, 77L, COMPOSITE)).isEqualTo(4L);
        assertThat(SourceSampler.entryChannelId(SourceKind.FOLLOW_ENTRY, 77L, COMPOSITE)).isEqualTo(1L);
        assertThat(SourceSampler.entryChannelId(SourceKind.SEARCH, 77L, COMPOSITE)).isNull();
        assertThatThrownBy(() -> SourceSampler.entryChannelId(SourceKind.HOT_ENTRY, 77L, Map.of()))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dwellStaysInRangeAndCompletionMatchesTheTypeRates() {
        Random random = new Random(2);
        int n = 20_000;
        int articleCompleted = 0;
        int videoCompleted = 0;
        long videoOver600 = 0;
        for (int i = 0; i < n; i++) {
            ViewDetailSampler.Dwell article = ViewDetailSampler.sample(ContentType.ARTICLE, random);
            ViewDetailSampler.Dwell video = ViewDetailSampler.sample(ContentType.VIDEO, random);
            assertThat(article.seconds()).isBetween(ViewDetailSampler.MIN_SECONDS, ViewDetailSampler.ARTICLE_MAX_SECONDS);
            assertThat(video.seconds()).isBetween(ViewDetailSampler.MIN_SECONDS, ViewDetailSampler.VIDEO_MAX_SECONDS);
            if (article.completed()) articleCompleted++;
            if (video.completed()) videoCompleted++;
            if (video.seconds() > 600) videoOver600++;
        }
        assertThat(articleCompleted / (double) n).isCloseTo(0.55, org.assertj.core.data.Offset.offset(0.02));
        assertThat(videoCompleted / (double) n).isCloseTo(0.35, org.assertj.core.data.Offset.offset(0.02));
        assertThat(videoOver600).isPositive();
    }

    @Test
    void completedViewsDwellLongerOnAverage() {
        Random random = new Random(3);
        double completedSum = 0, otherSum = 0;
        int completedN = 0, otherN = 0;
        for (int i = 0; i < 20_000; i++) {
            ViewDetailSampler.Dwell d = ViewDetailSampler.sample(ContentType.ARTICLE, random);
            if (d.completed()) { completedSum += d.seconds(); completedN++; } else { otherSum += d.seconds(); otherN++; }
        }
        assertThat(completedSum / completedN).isGreaterThan(otherSum / otherN * 1.3);
    }

    @Test
    void everyContentGetsItsDomainTagFirstPlusTwoOrThreeDistinctSharedTags() {
        Random random = new Random(4);
        Set<String> seenShared = new HashSet<>();
        int firstTagCount = 0;
        int[] countByTag = new int[TagCatalog.SHARED_TAGS.size()];
        for (int i = 0; i < 5_000; i++) {
            List<String> tags = TagCatalog.tagsFor("半导体", random);
            assertThat(tags.get(0)).isEqualTo("半导体");
            assertThat(tags).hasSizeBetween(3, 4).doesNotHaveDuplicates();
            tags.subList(1, tags.size()).forEach(t -> {
                assertThat(TagCatalog.SHARED_TAGS).contains(t);
                countByTag[TagCatalog.SHARED_TAGS.indexOf(t)]++;
                seenShared.add(t);
            });
        }
        assertThat(seenShared).hasSize(TagCatalog.SHARED_TAGS.size());
        assertThat(countByTag[0]).isGreaterThan(countByTag[TagCatalog.SHARED_TAGS.size() - 1] * 3);
    }

    @Test
    void searchKeywordsMixContentTagsDomainAndTheWholeVocabulary() {
        Random random = new Random(5);
        List<String> tags = List.of("半导体", "测试", "专利");
        int fromTags = 0, domain = 0, other = 0;
        for (int i = 0; i < 20_000; i++) {
            String keyword = TagCatalog.keyword(tags, "光", random);
            if (tags.contains(keyword)) fromTags++;
            else if (keyword.equals("光")) domain++;
            else { other++; assertThat(TagCatalog.SHARED_TAGS).contains(keyword); }
        }
        assertThat(fromTags / 20_000.0).isCloseTo(0.60, org.assertj.core.data.Offset.offset(0.05));
        assertThat(domain / 20_000.0).isCloseTo(0.25, org.assertj.core.data.Offset.offset(0.03));
        assertThat(other).isPositive();
    }

    @Test
    void unclickedImpressionsAverageOutToOneOverCtrMinusOne() {
        Random random = new Random(6);
        for (SourceKind kind : List.of(SourceKind.RECOMMEND_ENTRY, SourceKind.OWN_ENTRY, SourceKind.FOLLOW_ENTRY)) {
            long total = 0;
            int n = 50_000;
            for (int i = 0; i < n; i++) {
                total += ImpressionPlanner.unclickedImpressions(kind, random);
            }
            double expected = (1 - kind.ctr()) / kind.ctr();
            assertThat(total / (double) n).as(kind.name())
                .isCloseTo(expected, org.assertj.core.data.Offset.offset(expected * 0.03));
        }
    }
}
