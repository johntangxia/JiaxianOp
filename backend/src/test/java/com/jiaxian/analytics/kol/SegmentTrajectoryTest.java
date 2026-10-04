package com.jiaxian.analytics.kol;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentTrajectoryTest {

    private static HistoryPoint point(String asOf, Segment segment) {
        return new HistoryPoint(LocalDate.parse(asOf), 1, segment, segment.displayName(), false, 3, 3, 3);
    }

    @Test
    void noHistoryOrASinglePeriodHasNoChanges() {
        assertThat(SegmentTrajectory.changes(List.of())).isEmpty();
        assertThat(SegmentTrajectory.changes(List.of(point("2026-03-01", Segment.STAR_KOL)))).isEmpty();
    }

    @Test
    void anUnchangedSegmentHasNoChanges() {
        assertThat(SegmentTrajectory.changes(List.of(
            point("2026-02-15", Segment.STEADY), point("2026-02-22", Segment.STEADY),
            point("2026-03-01", Segment.STEADY)))).isEmpty();
    }

    @Test
    void everySegmentChangeIsRecordedAtThePeriodWhereItHappened() {
        List<SegmentChange> changes = SegmentTrajectory.changes(List.of(
            point("2026-02-08", Segment.RISING),
            point("2026-02-15", Segment.STEADY),
            point("2026-02-22", Segment.STEADY),
            point("2026-03-01", Segment.STAR_KOL),
            point("2026-03-08", Segment.CHURN_RISK)));

        assertThat(changes).containsExactly(
            new SegmentChange(LocalDate.parse("2026-02-15"), Segment.RISING, Segment.STEADY),
            new SegmentChange(LocalDate.parse("2026-03-01"), Segment.STEADY, Segment.STAR_KOL),
            new SegmentChange(LocalDate.parse("2026-03-08"), Segment.STAR_KOL, Segment.CHURN_RISK));
    }

    @Test
    void goingBackAndForthIsRecordedEachTime() {
        List<SegmentChange> changes = SegmentTrajectory.changes(List.of(
            point("2026-02-15", Segment.STEADY), point("2026-02-22", Segment.OBSERVE),
            point("2026-03-01", Segment.STEADY)));

        assertThat(changes).hasSize(2);
        assertThat(changes.get(1).from()).isEqualTo(Segment.OBSERVE);
        assertThat(changes.get(1).to()).isEqualTo(Segment.STEADY);
    }
}
