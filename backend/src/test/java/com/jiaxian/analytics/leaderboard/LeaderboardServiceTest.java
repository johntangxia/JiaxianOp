package com.jiaxian.analytics.leaderboard;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LeaderboardServiceTest {

    private static final List<LeaderboardEntry> ENTRIES = List.of(new LeaderboardEntry(1, 7, "员工-7", 300, 200, 100));

    private final LeaderboardQueryRepository repository = mock(LeaderboardQueryRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-04T08:00:00Z"), ZoneOffset.UTC);
    private final LeaderboardService service = new LeaderboardService(repository, clock);

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    @Test
    void defaultPeriodIsTheCurrentOneAndIsComputedLive() {
        when(repository.live(d(2026, 10, 1), d(2026, 11, 1), 50)).thenReturn(ENTRIES);

        Leaderboard board = service.leaderboard(PeriodType.MONTH, null, 50);

        assertThat(board.periodType()).isEqualTo(PeriodType.MONTH);
        assertThat(board.periodValue()).isEqualTo("2026-10");
        assertThat(board.status()).isEqualTo(Leaderboard.Status.IN_PROGRESS);
        assertThat(board.source()).isEqualTo(Leaderboard.Source.LIVE);
        assertThat(board.entries()).isEqualTo(ENTRIES);
    }

    @Test
    void explicitCurrentPeriodIsStillInProgress() {
        when(repository.live(d(2026, 1, 1), d(2027, 1, 1), 10)).thenReturn(ENTRIES);

        Leaderboard board = service.leaderboard(PeriodType.YEAR, "2026", 10);

        assertThat(board.periodValue()).isEqualTo("2026");
        assertThat(board.status()).isEqualTo(Leaderboard.Status.IN_PROGRESS);
        assertThat(board.source()).isEqualTo(Leaderboard.Source.LIVE);
    }

    @Test
    void endedPeriodWithASnapshotIsReadFromTheSnapshot() {
        when(repository.snapshotExists(PeriodType.MONTH, "2026-09")).thenReturn(true);
        when(repository.snapshot(PeriodType.MONTH, "2026-09", 50)).thenReturn(ENTRIES);

        Leaderboard board = service.leaderboard(PeriodType.MONTH, "2026-09", 50);

        assertThat(board.status()).isEqualTo(Leaderboard.Status.ARCHIVED);
        assertThat(board.source()).isEqualTo(Leaderboard.Source.SNAPSHOT);
        assertThat(board.entries()).isEqualTo(ENTRIES);
    }

    @Test
    void endedPeriodWithoutASnapshotFallsBackToLive() {
        when(repository.snapshotExists(PeriodType.MONTH, "2026-09")).thenReturn(false);
        when(repository.live(d(2026, 9, 1), d(2026, 10, 1), 50)).thenReturn(ENTRIES);

        Leaderboard board = service.leaderboard(PeriodType.MONTH, "2026-09", 50);

        assertThat(board.status()).isEqualTo(Leaderboard.Status.ARCHIVED);
        assertThat(board.source()).isEqualTo(Leaderboard.Source.LIVE);
        assertThat(board.entries()).isEqualTo(ENTRIES);
    }

    @Test
    void futurePeriodsAreRejected() {
        assertThatThrownBy(() -> service.leaderboard(PeriodType.MONTH, "2026-11", 50))
            .isInstanceOf(InvalidLeaderboardQueryException.class).hasMessageContaining("future");
        assertThatThrownBy(() -> service.leaderboard(PeriodType.YEAR, "2027", 50))
            .isInstanceOf(InvalidLeaderboardQueryException.class).hasMessageContaining("future");
    }

    @Test
    void malformedPeriodAndBadLimitAreRejected() {
        assertThatThrownBy(() -> service.leaderboard(PeriodType.MONTH, "2026-9", 50))
            .isInstanceOf(InvalidLeaderboardQueryException.class);
        assertThatThrownBy(() -> service.leaderboard(PeriodType.MONTH, "2026-09", 0))
            .isInstanceOf(InvalidLeaderboardQueryException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> service.leaderboard(PeriodType.MONTH, "2026-09", 101))
            .isInstanceOf(InvalidLeaderboardQueryException.class).hasMessageContaining("limit");
    }
}
