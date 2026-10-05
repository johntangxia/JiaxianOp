package com.jiaxian.analytics.channel;

import com.jiaxian.analytics.channel.ChannelQueryRepository.ContentSide;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContentMetricsFactoryTest {

    @Test
    void computesSharesAndConcentrationFromTheAggregates() {
        ContentSide side = new ContentSide(200, 25, new SourceMix(12, 28, 20, 80, 60));

        ContentMetrics metrics = ContentMetricsFactory.build(side, 30, List.of(60L, 50L, 40L, 30L, 20L));

        assertThat(metrics.pv()).isEqualTo(200);
        assertThat(metrics.uv()).isEqualTo(25);
        assertThat(metrics.interactions()).isEqualTo(30);
        assertThat(metrics.interactionRate()).isEqualTo(0.15);
        assertThat(metrics.top1Share()).isEqualTo(0.3);
        assertThat(metrics.top3Share()).isEqualTo(0.75);
        assertThat(metrics.ownEntryShare()).isEqualTo(0.06);
        assertThat(metrics.compositeDependency()).isEqualTo(0.14);
        assertThat(metrics.sourceMix()).isEqualTo(new SourceMix(12, 28, 20, 80, 60));
    }

    @Test
    void aShortContentListGivesTheSameShareForTop3AndTotal() {
        ContentMetrics metrics = ContentMetricsFactory.build(new ContentSide(100, 10, new SourceMix(0, 0, 0, 0, 100)), 0, List.of(70L, 30L));

        assertThat(metrics.top1Share()).isEqualTo(0.7);
        assertThat(metrics.top3Share()).isEqualTo(1.0);
    }

    @Test
    void withoutPvOnlyTheInteractionsSurvive() {
        ContentMetrics none = ContentMetricsFactory.build(null, 4, List.of());
        ContentMetrics zero = ContentMetricsFactory.build(new ContentSide(0, 0, SourceMix.EMPTY), 4, List.of());

        for (ContentMetrics metrics : List.of(none, zero)) {
            assertThat(metrics.pv()).isZero();
            assertThat(metrics.interactions()).isEqualTo(4);
            assertThat(metrics.interactionRate()).isNull();
            assertThat(metrics.ownEntryShare()).isNull();
            assertThat(metrics.top1Share()).isNull();
            assertThat(metrics.sourceMix()).isEqualTo(SourceMix.EMPTY);
        }
    }

    @Test
    void withoutAPerContentListThereIsNoConcentration() {
        ContentMetrics metrics = ContentMetricsFactory.build(new ContentSide(10, 5, new SourceMix(1, 1, 1, 1, 6)), 1, List.of());

        assertThat(metrics.top1Share()).isNull();
        assertThat(metrics.top3Share()).isNull();
        assertThat(metrics.interactionRate()).isEqualTo(0.1);
    }
}
