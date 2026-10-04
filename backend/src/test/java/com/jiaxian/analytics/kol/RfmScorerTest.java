package com.jiaxian.analytics.kol;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RfmScorerTest {

    private static BloggerMetrics metrics(long id, Integer recencyDays, int frequency, long monetary) {
        return new BloggerMetrics(id, (int) id, 0, recencyDays, frequency, monetary, 0, 0, 0, 0, 0);
    }

    @Test
    void emptyPopulationYieldsNoScores() {
        assertThat(RfmScorer.score(List.of())).isEmpty();
    }

    @Test
    void tenPeopleSpreadEvenlyAcrossTheFiveScores() {
        // id i：F = i-1（越大越好），R = 10-i 天（越小越好），M = (10-i)*100。
        // 分数 = 1 + (严格更差的人数 * 5) / 10：更差的人数 0..9 → 1,1,2,2,3,3,4,4,5,5
        List<BloggerMetrics> people = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            people.add(metrics(i, 10 - i, i - 1, (10 - i) * 100L));
        }

        Map<Long, RfmScores> scores = RfmScorer.score(people);

        int[] expectedFandR = {1, 1, 2, 2, 3, 3, 4, 4, 5, 5};
        int[] expectedM = {5, 5, 4, 4, 3, 3, 2, 2, 1, 1};
        for (int i = 1; i <= 10; i++) {
            RfmScores s = scores.get((long) i);
            // R：id 越大天数越小 → 越近 → 分越高，和 F 的模式相同
            assertThat(s.r()).as("R of %d", i).isEqualTo(expectedFandR[i - 1]);
            assertThat(s.f()).as("F of %d", i).isEqualTo(expectedFandR[i - 1]);
            assertThat(s.m()).as("M of %d", i).isEqualTo(expectedM[i - 1]);
        }
    }

    @Test
    void tiesShareTheSameScoreAndMissingRecencyIsTheWorst() {
        // 4 人：F = 1,2,2,3；R = 0,5,5,空；M 全相同
        List<BloggerMetrics> people = List.of(
            metrics(1, 0, 1, 50), metrics(2, 5, 2, 50), metrics(3, 5, 2, 50), metrics(4, null, 3, 50));

        Map<Long, RfmScores> scores = RfmScorer.score(people);

        // F：比自己小的人数 0,1,1,3 → 1 + (c*5)/4 = 1,2,2,4
        assertThat(scores.get(1L).f()).isEqualTo(1);
        assertThat(scores.get(2L).f()).isEqualTo(2);
        assertThat(scores.get(3L).f()).isEqualTo(2);
        assertThat(scores.get(4L).f()).isEqualTo(4);
        // R：比自己更差（天数更大）的人数 3,1,1,0 → 4,2,2,1
        assertThat(scores.get(1L).r()).isEqualTo(4);
        assertThat(scores.get(2L).r()).isEqualTo(2);
        assertThat(scores.get(3L).r()).isEqualTo(2);
        assertThat(scores.get(4L).r()).isEqualTo(1);
        // M：全员相同 → 全员 1 分
        assertThat(scores.values()).allSatisfy(s -> assertThat(s.m()).isEqualTo(1));
    }

    @Test
    void aSinglePersonScoresOne() {
        RfmScores only = RfmScorer.score(List.of(metrics(1, 3, 10, 999))).get(1L);

        assertThat(only).isEqualTo(new RfmScores(1, 1, 1));
    }

    @Test
    void averageIsTheMeanOfTheThreeScores() {
        assertThat(new RfmScores(4, 4, 2).average()).isEqualTo(10.0 / 3);
    }
}
