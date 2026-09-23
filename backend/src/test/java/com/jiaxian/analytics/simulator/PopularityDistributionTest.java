package com.jiaxian.analytics.simulator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class PopularityDistributionTest {

    @Test
    void generatesWeightsBetweenZeroExclusiveAndOneInclusive() {
        PopularityDistribution distribution = new PopularityDistribution(new Random(42));

        List<Double> weights = distribution.generateWeights(1000);

        assertThat(weights).hasSize(1000);
        assertThat(weights).allMatch(w -> w > 0.0 && w <= 1.0);
    }

    @Test
    void topDecileCapturesMajorityOfTotalWeight() {
        PopularityDistribution distribution = new PopularityDistribution(new Random(42));

        List<Double> weights = distribution.generateWeights(1000);
        double total = weights.stream().mapToDouble(Double::doubleValue).sum();
        double topDecileSum = weights.stream()
            .sorted((a, b) -> Double.compare(b, a))
            .limit(100)
            .mapToDouble(Double::doubleValue)
            .sum();

        assertThat(topDecileSum / total).isGreaterThan(0.5);
    }

    @Test
    void sameSeedProducesSameSequence() {
        PopularityDistribution d1 = new PopularityDistribution(new Random(7));
        PopularityDistribution d2 = new PopularityDistribution(new Random(7));

        assertThat(d1.generateWeights(50)).isEqualTo(d2.generateWeights(50));
    }
}
