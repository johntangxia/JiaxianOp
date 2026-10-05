package com.jiaxian.analytics.people;

import com.jiaxian.analytics.channel.ChannelQueryRepository.ContentSide;
import com.jiaxian.analytics.channel.SourceMix;
import com.jiaxian.analytics.drill.ExposureMetrics;
import com.jiaxian.analytics.drill.SubjectQueryRepository;
import com.jiaxian.analytics.drill.SubjectType;
import com.jiaxian.analytics.people.PeopleQueryRepository.AccountTypeFigures;
import com.jiaxian.analytics.pv.Granularity;
import com.jiaxian.analytics.reader.Member;
import com.jiaxian.analytics.reader.ReaderQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreatorServiceTest {

    private static final LocalDate FROM = LocalDate.of(2025, 6, 1);
    private static final LocalDate TO = LocalDate.of(2025, 6, 30);

    private final PeopleQueryRepository people = mock(PeopleQueryRepository.class);
    private final ReaderQueryRepository readers = mock(ReaderQueryRepository.class);
    private final SubjectQueryRepository subjects = mock(SubjectQueryRepository.class);
    private final AsOfResolver asOf = new AsOfResolver(readers, Clock.fixed(LocalDate.of(2025, 9, 1).atStartOfDay(ZoneId.systemDefault()).toInstant(),
        ZoneId.systemDefault()));
    private final CreatorService service = new CreatorService(people, readers, subjects, asOf);

    private static Member member(long id, String dept, LocalDate join) {
        return new Member(id, "员工" + id, dept, join);
    }

    private static ContentSide side(long pv, long uv) {
        return new ContentSide(pv, uv, SourceMix.EMPTY);
    }

    @BeforeEach
    void defaults() {
        when(readers.members()).thenReturn(List.of(member(1, "甲", LocalDate.of(2024, 1, 1)), member(2, "乙", LocalDate.of(2024, 1, 1)),
            member(3, "甲", LocalDate.of(2024, 1, 1)), member(4, "乙", LocalDate.of(2025, 7, 1))));
        when(readers.depts()).thenReturn(List.of("乙", "甲"));
        when(readers.latestActiveDay()).thenReturn(Optional.of(TO));
        when(people.postDays(any(), any())).thenReturn(List.of());
        when(people.firstPostDays()).thenReturn(Map.of());
        when(subjects.contentSide(any(), any(), any(), any())).thenReturn(Map.of());
        when(subjects.interactions(any(), any(), any(), any())).thenReturn(Map.of());
        when(subjects.exposure(any(), any(), any(), any())).thenReturn(Map.of());
        when(people.newFollowersByEmployee(any(), any())).thenReturn(Map.of());
        when(people.accountTypes()).thenReturn(Map.of());
        when(people.accountTypeSupply(any(), any())).thenReturn(List.of());
        when(people.lifetimeFacts(any())).thenReturn(List.of());
        when(people.latestKolDate(any())).thenReturn(Optional.empty());
    }

    // ---- 校验 ----

    @Test
    void rejectsBadRangesPeriodsGranularityAndPaging() {
        assertThatThrownBy(() -> service.overview(TO, FROM, Granularity.MONTH, null)).isInstanceOf(InvalidPeopleQueryException.class);
        assertThatThrownBy(() -> service.overview(FROM, FROM.plusDays(366), Granularity.MONTH, null))
            .isInstanceOf(InvalidPeopleQueryException.class).hasMessageContaining("366");
        assertThatThrownBy(() -> service.overview(FROM, TO, Granularity.YEAR, null)).isInstanceOf(InvalidPeopleQueryException.class);
        assertThatThrownBy(() -> service.overview(FROM, TO, Granularity.MONTH, 13)).isInstanceOf(InvalidPeopleQueryException.class);
        assertThatThrownBy(() -> service.overview(FROM, TO, Granularity.MONTH, 0)).isInstanceOf(InvalidPeopleQueryException.class);
        assertThatThrownBy(() -> service.ranking(FROM, TO, "NAME", null, null, null)).isInstanceOf(InvalidPeopleQueryException.class);
        assertThatThrownBy(() -> service.ranking(FROM, TO, null, null, 0, null)).isInstanceOf(InvalidPeopleQueryException.class);
        assertThatThrownBy(() -> service.ranking(FROM, TO, null, null, 101, null)).isInstanceOf(InvalidPeopleQueryException.class);
        assertThatThrownBy(() -> service.ranking(FROM, TO, null, null, null, -1)).isInstanceOf(InvalidPeopleQueryException.class);
        assertThatThrownBy(() -> service.concentration(TO, FROM)).isInstanceOf(InvalidPeopleQueryException.class);
        assertThat(service.overview(FROM, TO, Granularity.MONTH, 12)).isNotNull();
    }

    // ---- 总览 ----

    @Test
    void overviewComparesWithThePreviousWindowOfTheSameLength() {
        // 当前窗口 06-01~06-30（30 天），上一个窗口 05-02~05-31
        when(people.firstPostDays()).thenReturn(Map.of(1L, LocalDate.of(2025, 5, 10), 2L, LocalDate.of(2025, 6, 5)));
        when(people.postDays(any(), any())).thenReturn(List.of(new PostDay(1, LocalDate.of(2025, 5, 10), 1),
            new PostDay(1, LocalDate.of(2025, 6, 6), 2), new PostDay(2, LocalDate.of(2025, 6, 5), 1)));

        CreatorOverviewReport report = service.overview(FROM, TO, Granularity.MONTH, null);

        assertThat(report.summary().creators()).isEqualTo(2);
        assertThat(report.summary().newCreators()).isEqualTo(1);
        assertThat(report.summary().previousCreators()).isEqualTo(1);
        assertThat(report.summary().creatorShare()).isEqualTo(2 / 3.0);     // 员工 4 在窗口结束后才加入
        assertThat(report.series()).hasSize(1);
        assertThat(report.periods()).isEqualTo(6);
        assertThat(report.cohorts()).extracting("cohortStart").contains(LocalDate.of(2025, 6, 1));
    }

    // ---- 排行 ----

    private void rankingFixture() {
        when(people.postDays(any(), any())).thenReturn(List.of(new PostDay(1, LocalDate.of(2025, 6, 2), 3),
            new PostDay(2, LocalDate.of(2025, 6, 3), 1), new PostDay(3, LocalDate.of(2025, 6, 4), 2),
            new PostDay(99, LocalDate.of(2025, 6, 4), 5)));      // 99 不是员工，忽略
        when(subjects.contentSide(SubjectType.BLOGGER, com.jiaxian.analytics.drill.SubjectFilter.NONE, FROM, TO))
            .thenReturn(Map.of(1L, side(100, 40), 2L, side(300, 90)));
        when(subjects.interactions(SubjectType.BLOGGER, com.jiaxian.analytics.drill.SubjectFilter.NONE, FROM, TO))
            .thenReturn(Map.of(1L, 30L, 2L, 30L));
        when(subjects.exposure(SubjectType.BLOGGER, com.jiaxian.analytics.drill.SubjectFilter.NONE, FROM, TO))
            .thenReturn(Map.of(1L, new ExposureMetrics(0, 0, null, 50.0, 0.8), 2L, new ExposureMetrics(0, 0, null, 50.0, 0.4)));
        when(people.newFollowersByEmployee(FROM, TO)).thenReturn(Map.of(3L, 9L, 1L, 4L));
    }

    @Test
    void rankingSortsByPvByDefaultAndPutsMissingValuesLast() {
        rankingFixture();

        CreatorRankingReport report = service.ranking(FROM, TO, null, null, null, null);

        assertThat(report.sort()).isEqualTo("PV");
        assertThat(report.total()).isEqualTo(3);
        assertThat(report.creators()).extracting(CreatorRow::employeeId).containsExactly(2L, 1L, 3L);
        assertThat(report.creators().get(2).pv()).isZero();
        assertThat(report.creators().get(2).interactionRate()).isNull();
        assertThat(report.creators().get(0).interactionRate()).isEqualTo(0.1);
        assertThat(report.depts()).containsExactly("乙", "甲");
    }

    @Test
    void rankingSupportsEverySortKey() {
        rankingFixture();

        assertThat(service.ranking(FROM, TO, "posts", null, null, null).creators()).extracting(CreatorRow::employeeId).containsExactly(1L, 3L, 2L);
        assertThat(service.ranking(FROM, TO, "INTERACTION_RATE", null, null, null).creators()).extracting(CreatorRow::employeeId)
            .containsExactly(1L, 2L, 3L);        // 0.3 > 0.1 > 缺失
        assertThat(service.ranking(FROM, TO, "COMPLETION", null, null, null).creators()).extracting(CreatorRow::employeeId)
            .containsExactly(1L, 2L, 3L);
        assertThat(service.ranking(FROM, TO, "NEW_FOLLOWERS", null, null, null).creators()).extracting(CreatorRow::employeeId)
            .containsExactly(3L, 1L, 2L);
    }

    @Test
    void rankingExportReturnsEveryCreatorInTheRankingOrderAndValidates() {
        rankingFixture();

        List<CreatorRow> all = service.rankingExport(FROM, TO, "POSTS", null);

        assertThat(all).extracting(CreatorRow::employeeId).containsExactly(1L, 3L, 2L);
        assertThat(service.rankingExport(FROM, TO, null, "甲")).extracting(CreatorRow::employeeId).containsExactly(1L, 3L);
        assertThatThrownBy(() -> service.rankingExport(TO, FROM, null, null)).isInstanceOf(InvalidPeopleQueryException.class);
        assertThatThrownBy(() -> service.rankingExport(FROM, TO, "NAME", null)).isInstanceOf(InvalidPeopleQueryException.class);
    }

    @Test
    void rankingFiltersByDeptAndPages() {
        rankingFixture();

        CreatorRankingReport jia = service.ranking(FROM, TO, "POSTS", "甲", null, null);
        assertThat(jia.total()).isEqualTo(2);
        assertThat(jia.creators()).extracting(CreatorRow::employeeId).containsExactly(1L, 3L);

        CreatorRankingReport page = service.ranking(FROM, TO, "POSTS", null, 1, 1);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.creators()).extracting(CreatorRow::employeeId).containsExactly(3L);
        assertThat(page.limit()).isEqualTo(1);
        assertThat(page.offset()).isEqualTo(1);
    }

    // ---- 成长阶梯 ----

    private static LifetimeFacts facts(long id, Integer view, Integer interaction, Integer post, long recentPosts) {
        LocalDateTime base = LocalDateTime.of(2025, 1, 10, 9, 0);
        return new LifetimeFacts(id, base, view == null ? null : base.plusDays(view), interaction == null ? null : base.plusDays(interaction),
            post == null ? null : base.plusDays(post), recentPosts, base.plusDays(50));
    }

    @Test
    void ladderPutsEveryMemberInTheirHighestStageAndMeasuresTimeToEachStep() {
        LocalDate asOfDay = LocalDate.of(2025, 6, 30);
        when(readers.members()).thenReturn(List.of(member(1, "甲", LocalDate.of(2025, 1, 10)), member(2, "甲", LocalDate.of(2025, 1, 10)),
            member(3, "甲", LocalDate.of(2025, 1, 10)), member(4, "甲", LocalDate.of(2025, 1, 10)), member(5, "甲", LocalDate.of(2025, 7, 1))));
        when(people.lifetimeFacts(asOfDay)).thenReturn(List.of(facts(1, 1, null, null, 0), facts(2, 2, 4, null, 0),
            facts(3, 1, 2, 6, 5)));
        when(people.latestKolDate(asOfDay)).thenReturn(Optional.of(LocalDate.of(2025, 6, 28)));
        when(people.starKols(LocalDate.of(2025, 6, 28))).thenReturn(Set.of(3L));

        CreatorLadderReport report = service.ladder(asOfDay);

        assertThat(report.members()).isEqualTo(4);        // 员工 5 还没加入
        assertThat(report.kolDate()).isEqualTo(LocalDate.of(2025, 6, 28));
        assertThat(report.stages()).extracting(LadderStageCount::stage, LadderStageCount::count).containsExactly(
            org.assertj.core.groups.Tuple.tuple(CreatorStage.INACTIVE, 1L), org.assertj.core.groups.Tuple.tuple(CreatorStage.READER, 1L),
            org.assertj.core.groups.Tuple.tuple(CreatorStage.INTERACTOR, 1L), org.assertj.core.groups.Tuple.tuple(CreatorStage.CREATOR, 0L),
            org.assertj.core.groups.Tuple.tuple(CreatorStage.ACTIVE_CREATOR, 0L), org.assertj.core.groups.Tuple.tuple(CreatorStage.KOL, 1L));
        assertThat(report.stages().get(5).share()).isEqualTo(0.25);
        assertThat(report.reach()).extracting(LadderReach::step).containsExactly("VIEW", "INTERACT", "POST");
        assertThat(report.reach().get(0).reached()).isEqualTo(3);
        assertThat(report.reach().get(0).medianDays()).isEqualTo(1.0);        // 1、2、1 天
        assertThat(report.reach().get(1).reached()).isEqualTo(2);
        assertThat(report.reach().get(1).medianDays()).isEqualTo(3.0);        // 4、2 天 → 3
        assertThat(report.reach().get(2).reached()).isEqualTo(1);
        assertThat(report.reach().get(2).share()).isEqualTo(0.25);
    }

    @Test
    void ladderLeavesMembersWhoJoinedBeforeTheDataStartedOutOfTheTimings() {
        LocalDate asOfDay = LocalDate.of(2025, 6, 30);
        when(readers.members()).thenReturn(List.of(member(1, "甲", LocalDate.of(2024, 6, 1)), member(2, "甲", LocalDate.of(2025, 1, 10))));
        when(readers.loginPostSpan()).thenReturn(Optional.of(new ReaderQueryRepository.DateSpan(LocalDate.of(2025, 1, 1), asOfDay)));
        // 员工 1 加入得更早，数据开始后才第一次浏览（距加入 ~220 天，会把中位数拉偏）；员工 2 在数据里加入，3 天后首次浏览
        when(people.lifetimeFacts(asOfDay)).thenReturn(List.of(
            new LifetimeFacts(1, LocalDateTime.of(2025, 1, 2, 9, 0), LocalDateTime.of(2025, 1, 2, 9, 0), null, null, 0, LocalDateTime.of(2025, 1, 2, 9, 0)),
            new LifetimeFacts(2, LocalDateTime.of(2025, 1, 13, 9, 0), LocalDateTime.of(2025, 1, 13, 9, 0), null, null, 0, LocalDateTime.of(2025, 1, 13, 9, 0))));

        CreatorLadderReport report = service.ladder(asOfDay);

        assertThat(report.reach().get(0).reached()).isEqualTo(2);              // 到达人数两人都算
        assertThat(report.reach().get(0).medianDays()).isEqualTo(3.0);         // 耗时只看员工 2
    }

    @Test
    void ladderDefaultsTheAsOfDayToTheLatestLoginOrPostDay() {
        when(readers.loginPostSpan()).thenReturn(Optional.of(new ReaderQueryRepository.DateSpan(FROM, TO)));

        assertThat(service.ladder(null).asOf()).isEqualTo(TO);
    }

    @Test
    void ladderWithoutAnyKolRebuildHasNoKolDate() {
        assertThat(service.ladder(TO).kolDate()).isNull();
    }

    // ---- 集中度 ----

    @Test
    void concentrationReportsGiniSharesAndAccountTypes() {
        when(people.postDays(any(), any())).thenReturn(List.of(new PostDay(1, LocalDate.of(2025, 6, 2), 1),
            new PostDay(2, LocalDate.of(2025, 6, 3), 1), new PostDay(3, LocalDate.of(2025, 6, 4), 1), new PostDay(4, LocalDate.of(2025, 6, 4), 1)));
        when(subjects.contentSide(SubjectType.BLOGGER, com.jiaxian.analytics.drill.SubjectFilter.NONE, FROM, TO))
            .thenReturn(Map.of(1L, side(10, 5), 2L, side(10, 5), 3L, side(10, 5), 4L, side(10, 5)));
        when(people.accountTypes()).thenReturn(Map.of(10L, "PERSONAL", 11L, "TEAM", 12L, "TEAM"));
        when(subjects.contentSide(SubjectType.ACCOUNT, com.jiaxian.analytics.drill.SubjectFilter.NONE, FROM, TO))
            .thenReturn(Map.of(10L, side(100, 50), 11L, side(60, 30), 12L, side(40, 20)));
        when(subjects.interactions(SubjectType.ACCOUNT, com.jiaxian.analytics.drill.SubjectFilter.NONE, FROM, TO))
            .thenReturn(Map.of(10L, 10L, 11L, 3L, 12L, 3L));
        when(people.accountTypeSupply(FROM, TO)).thenReturn(List.of(new AccountTypeFigures("PERSONAL", 5, 3),
            new AccountTypeFigures("TEAM", 2, 4)));

        CreatorConcentrationReport report = service.concentration(FROM, TO);

        assertThat(report.creators()).isEqualTo(4);
        assertThat(report.gini()).isEqualTo(0.0);
        assertThat(report.shares()).hasSize(4);
        assertThat(report.accountTypes()).extracting(AccountTypeRow::type, AccountTypeRow::posts, AccountTypeRow::creators, AccountTypeRow::pv,
            AccountTypeRow::interactions).containsExactly(
            org.assertj.core.groups.Tuple.tuple("PERSONAL", 5L, 3L, 100L, 10L), org.assertj.core.groups.Tuple.tuple("TEAM", 2L, 4L, 100L, 6L));
        assertThat(report.accountTypes().get(0).interactionRate()).isEqualTo(0.1);
        assertThat(report.accountTypes().get(1).interactionRate()).isEqualTo(0.06);
    }

    @Test
    void concentrationWithNoCreatorsHasNoGiniAndZeroedAccountTypes() {
        CreatorConcentrationReport report = service.concentration(FROM, TO);

        assertThat(report.creators()).isZero();
        assertThat(report.gini()).isNull();
        assertThat(report.accountTypes()).extracting(AccountTypeRow::posts).containsExactly(0L, 0L);
        assertThat(report.accountTypes()).extracting(AccountTypeRow::interactionRate).containsOnlyNulls();
    }
}
