package com.jiaxian.analytics.kol;

import com.jiaxian.analytics.repository.ActiveActionLogRepository;
import com.jiaxian.analytics.repository.EmployeeRepository;
import com.jiaxian.analytics.repository.PointsLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class BloggerListServiceIT {

    private static final LocalDate AS_OF = LocalDate.of(2019, 6, 30);

    @Autowired private BloggerListService service;
    @Autowired private KolRebuildService rebuildService;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ActiveActionLogRepository activeActionLogRepository;
    @Autowired private PointsLedgerRepository pointsLedgerRepository;

    private KolTestFixtures.ChurnScenario s;

    @BeforeEach
    void setUp() {
        s = new KolTestFixtures(employeeRepository, activeActionLogRepository, pointsLedgerRepository)
            .churnScenario();
        rebuildService.rebuild(AS_OF);
    }

    @Test
    void listsEveryoneByRankWithScoresSegmentAndRecommendation() {
        BloggerList list = service.list(AS_OF, null, null, 50, 0);

        assertThat(list.asOf()).isEqualTo(AS_OF);
        assertThat(list.total()).isEqualTo(3);
        assertThat(list.items()).extracting(BloggerItem::employeeId)
            .containsExactly(s.q().getId(), s.r().getId(), s.p().getId());

        BloggerItem q = list.items().get(0);
        assertThat(q.name()).isEqualTo("KOL测试员工Q");
        assertThat(q.rank()).isEqualTo(1);
        assertThat(q.rollingPoints12m()).isEqualTo(600);
        assertThat(q.recencyDays()).isEqualTo(0);
        assertThat(q.frequency()).isEqualTo(30);
        assertThat(q.monetary()).isEqualTo(600);
        assertThat(q.rScore()).isEqualTo(2);
        assertThat(q.fScore()).isEqualTo(4);
        assertThat(q.mScore()).isEqualTo(4);
        assertThat(q.segment()).isEqualTo(Segment.STEADY);
        assertThat(q.segmentName()).isEqualTo("稳定贡献者");
        assertThat(q.churnAlert()).isFalse();
        assertThat(q.recommendation()).contains("常规运营");

        BloggerItem p = list.items().get(2);
        assertThat(p.recencyDays()).isEqualTo(81);
        assertThat(p.churnAlert()).isTrue();
        // (DORMANT, ALERT) 没有文案 → 回退到 (DORMANT, NO_ALERT)
        assertThat(p.recommendation()).contains("低成本触达");
    }

    @Test
    void segmentCountsAreUnfilteredAndTotalIsFiltered() {
        BloggerList dormant = service.list(AS_OF, Segment.DORMANT, null, 50, 0);

        assertThat(dormant.total()).isEqualTo(2);
        assertThat(dormant.items()).extracting(BloggerItem::employeeId).containsExactly(s.r().getId(), s.p().getId());
        assertThat(dormant.segmentCounts().get(Segment.DORMANT)).isEqualTo(2);
        assertThat(dormant.segmentCounts().get(Segment.STEADY)).isEqualTo(1);
        assertThat(dormant.segmentCounts().get(Segment.STAR_KOL)).isZero();
    }

    @Test
    void filtersByAlert() {
        assertThat(service.list(AS_OF, null, true, 50, 0).items())
            .extracting(BloggerItem::employeeId).containsExactly(s.p().getId());
        assertThat(service.list(AS_OF, null, false, 50, 0).items())
            .extracting(BloggerItem::employeeId).containsExactly(s.q().getId(), s.r().getId());
        assertThat(service.list(AS_OF, Segment.STEADY, true, 50, 0).items()).isEmpty();
    }

    @Test
    void paginates() {
        BloggerList page = service.list(AS_OF, null, null, 1, 1);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).extracting(BloggerItem::employeeId).containsExactly(s.r().getId());
    }

    @Test
    void anAsOfWithoutARebuildIsEmpty() {
        BloggerList list = service.list(LocalDate.of(2019, 1, 1), null, null, 50, 0);

        assertThat(list.asOf()).isEqualTo(LocalDate.of(2019, 1, 1));
        assertThat(list.total()).isZero();
        assertThat(list.items()).isEmpty();
    }

    @Test
    void rejectsBadLimitAndOffset() {
        assertThatThrownBy(() -> service.list(AS_OF, null, null, 0, 0))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> service.list(AS_OF, null, null, 1001, 0))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> service.list(AS_OF, null, null, 50, -1))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("offset");
    }
}
