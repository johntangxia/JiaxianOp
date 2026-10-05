package com.jiaxian.analytics.growth;

import com.jiaxian.analytics.channel.ChannelCockpitService;
import com.jiaxian.analytics.channel.ChannelCockpitService.ChannelSnapshot;
import com.jiaxian.analytics.channel.ChannelPeriod;
import com.jiaxian.analytics.channel.ChannelQueryRepository.ContentSide;
import com.jiaxian.analytics.channel.ContentMetrics;
import com.jiaxian.analytics.channel.DistributionMetrics;
import com.jiaxian.analytics.channel.SourceMix;
import com.jiaxian.analytics.channel.SupplyMetrics;
import com.jiaxian.analytics.drill.SubjectFilter;
import com.jiaxian.analytics.drill.SubjectQueryRepository;
import com.jiaxian.analytics.drill.SubjectType;
import com.jiaxian.analytics.reader.Member;
import com.jiaxian.analytics.reader.ReaderQueryRepository;
import com.jiaxian.analytics.reader.ReaderQueryRepository.MemberActivity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetricEvaluatorTest {

    private static final LocalDate FROM = LocalDate.of(2025, 6, 1);
    private static final LocalDate TO = LocalDate.of(2025, 6, 30);

    private final ChannelCockpitService cockpit = mock(ChannelCockpitService.class);
    private final SubjectQueryRepository subjects = mock(SubjectQueryRepository.class);
    private final ReaderQueryRepository readers = mock(ReaderQueryRepository.class);
    private final MetricEvaluator evaluator = new MetricEvaluator(cockpit, subjects, readers);

    private void stubChannel() {
        ContentMetrics content = new ContentMetrics(200, 40, 30, 0.15, 0.3, 0.7, new SourceMix(10, 30, 20, 100, 40), 0.05, 0.15);
        ChannelPeriod period = new ChannelPeriod(new DistributionMetrics(100, 12, 0.12, 30, 8, 60.0), content,
            new SupplyMetrics(9, 4), null);
        ChannelPeriod composite = new ChannelPeriod(new DistributionMetrics(100, 6, 0.06, 30, 8, 60.0), null, null, null);
        when(cockpit.snapshot(FROM, TO)).thenReturn(List.of(
            new ChannelSnapshot(1, "推荐", "综合频道", composite), new ChannelSnapshot(10, "领域甲", "领域频道", period)));
    }

    @Test
    void channelMetricsComeFromTheCockpitSnapshot() {
        stubChannel();
        MetricEvaluator.Context context = evaluator.context();

        assertThat(context.evaluate(GrowthEntityType.CHANNEL, 10L, null, TaskMetric.PV, FROM, TO)).isEqualTo(200.0);
        assertThat(context.evaluate(GrowthEntityType.CHANNEL, 10L, null, TaskMetric.OWN_ENTRY_SHARE, FROM, TO)).isEqualTo(0.05);
        assertThat(context.evaluate(GrowthEntityType.CHANNEL, 10L, null, TaskMetric.OWN_CTR, FROM, TO)).isEqualTo(0.12);
        assertThat(context.evaluate(GrowthEntityType.CHANNEL, 10L, null, TaskMetric.INTERACTION_RATE, FROM, TO)).isEqualTo(0.15);
        assertThat(context.evaluate(GrowthEntityType.CHANNEL, 10L, null, TaskMetric.POSTS, FROM, TO)).isEqualTo(9.0);
        // 同一个窗口只查一次
        verify(cockpit, times(1)).snapshot(FROM, TO);
    }

    @Test
    void anUnknownOrCompositeChannelHasNoValue() {
        stubChannel();
        MetricEvaluator.Context context = evaluator.context();

        assertThat(context.evaluate(GrowthEntityType.CHANNEL, 999L, null, TaskMetric.PV, FROM, TO)).isNull();
        assertThat(context.evaluate(GrowthEntityType.CHANNEL, 1L, null, TaskMetric.PV, FROM, TO)).isNull();
    }

    @Test
    void accountAndBloggerMetricsUseTheSubjectQueries() {
        SubjectFilter filter = SubjectFilter.ofIds(7L);
        when(subjects.contentSide(SubjectType.ACCOUNT, filter, FROM, TO)).thenReturn(Map.of(7L, new ContentSide(50, 10, new SourceMix(1, 2, 3, 4, 40))));
        when(subjects.interactions(SubjectType.ACCOUNT, filter, FROM, TO)).thenReturn(Map.of(7L, 5L));
        when(subjects.supply(SubjectType.ACCOUNT, filter, FROM, TO)).thenReturn(Map.of(7L, new SupplyMetrics(3, 2)));
        MetricEvaluator.Context context = evaluator.context();

        assertThat(context.evaluate(GrowthEntityType.ACCOUNT, 7L, null, TaskMetric.PV, FROM, TO)).isEqualTo(50.0);
        assertThat(context.evaluate(GrowthEntityType.ACCOUNT, 7L, null, TaskMetric.INTERACTION_RATE, FROM, TO)).isEqualTo(0.1);
        assertThat(context.evaluate(GrowthEntityType.ACCOUNT, 7L, null, TaskMetric.POSTS, FROM, TO)).isEqualTo(3.0);
        verify(subjects, times(1)).contentSide(SubjectType.ACCOUNT, filter, FROM, TO);
    }

    @Test
    void anObjectWithoutDataHasZeroCountsAndNoRatios() {
        MetricEvaluator.Context context = evaluator.context();

        assertThat(context.evaluate(GrowthEntityType.BLOGGER, 8L, null, TaskMetric.PV, FROM, TO)).isEqualTo(0.0);
        assertThat(context.evaluate(GrowthEntityType.BLOGGER, 8L, null, TaskMetric.POSTS, FROM, TO)).isEqualTo(0.0);
        assertThat(context.evaluate(GrowthEntityType.BLOGGER, 8L, null, TaskMetric.INTERACTION_RATE, FROM, TO)).isNull();
    }

    private void stubReaders() {
        when(readers.members()).thenReturn(List.of(new Member(1, "甲", "半导体", null), new Member(2, "乙", "半导体", null),
            new Member(3, "丙", "光", null), new Member(4, "丁", "光", null), new Member(5, "戊", null, null)));
        when(readers.memberActivity(FROM, TO)).thenReturn(List.of(
            new MemberActivity(1, 0, 0, 3, 3, 2, TO), new MemberActivity(3, 0, 1, 3, 4, 2, TO), new MemberActivity(4, 0, 0, 0, 1, 1, TO)));
    }

    @Test
    void departmentMetricsCountActiveMembersOfThatDepartment() {
        stubReaders();
        MetricEvaluator.Context context = evaluator.context();

        assertThat(context.evaluate(GrowthEntityType.DEPARTMENT, null, "半导体", TaskMetric.PENETRATION, FROM, TO)).isEqualTo(0.5);
        assertThat(context.evaluate(GrowthEntityType.DEPARTMENT, null, "光", TaskMetric.ACTIVE_USERS, FROM, TO)).isEqualTo(2.0);
        assertThat(context.evaluate(GrowthEntityType.DEPARTMENT, null, "光", TaskMetric.PENETRATION, FROM, TO)).isEqualTo(1.0);
        assertThat(context.evaluate(GrowthEntityType.DEPARTMENT, null, "未分配部门", TaskMetric.ACTIVE_USERS, FROM, TO)).isEqualTo(0.0);
        verify(readers, times(1)).members();
        verify(readers, times(1)).memberActivity(FROM, TO);
    }

    @Test
    void overallMetricsCoverEveryMember() {
        stubReaders();
        MetricEvaluator.Context context = evaluator.context();

        assertThat(context.evaluate(GrowthEntityType.OVERALL, null, null, TaskMetric.ACTIVE_USERS, FROM, TO)).isEqualTo(3.0);
        assertThat(context.evaluate(GrowthEntityType.OVERALL, null, null, TaskMetric.ACTIVE_RATE, FROM, TO)).isEqualTo(0.6);
    }

    @Test
    void metricsThatDoNotApplyToTheObjectTypeGiveNull() {
        stubChannel();
        stubReaders();
        MetricEvaluator.Context context = evaluator.context();

        assertThat(context.evaluate(GrowthEntityType.CHANNEL, 10L, null, TaskMetric.PENETRATION, FROM, TO)).isNull();
        assertThat(context.evaluate(GrowthEntityType.DEPARTMENT, null, "光", TaskMetric.PV, FROM, TO)).isNull();
        assertThat(context.evaluate(GrowthEntityType.OVERALL, null, null, TaskMetric.OWN_CTR, FROM, TO)).isNull();
    }

    @Test
    void aNewContextStartsWithAnEmptyCache() {
        stubChannel();

        evaluator.context().evaluate(GrowthEntityType.CHANNEL, 10L, null, TaskMetric.PV, FROM, TO);
        evaluator.context().evaluate(GrowthEntityType.CHANNEL, 10L, null, TaskMetric.PV, FROM, TO);

        verify(cockpit, times(2)).snapshot(FROM, TO);
    }
}
