package com.jiaxian.analytics.growth;

import com.jiaxian.analytics.channel.ChannelCockpitService;
import com.jiaxian.analytics.channel.ChannelCockpitService.ChannelSnapshot;
import com.jiaxian.analytics.channel.ChannelPeriod;
import com.jiaxian.analytics.channel.ContentMetrics;
import com.jiaxian.analytics.channel.ContentMetricsFactory;
import com.jiaxian.analytics.channel.ChannelQueryRepository.ContentSide;
import com.jiaxian.analytics.channel.SupplyMetrics;
import com.jiaxian.analytics.drill.SubjectFilter;
import com.jiaxian.analytics.drill.SubjectQueryRepository;
import com.jiaxian.analytics.drill.SubjectType;
import com.jiaxian.analytics.reader.Member;
import com.jiaxian.analytics.reader.ReaderQueryRepository;
import com.jiaxian.analytics.reader.ReaderQueryRepository.MemberActivity;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 算任务指标：按对象类型从已有的驾驶舱、下钻、读者仓库取窗口内的值。没有数据的比例指标返回 null，计数指标返回 0。
 * 同一个 {@link Context}（一次请求）内相同窗口的查询结果会缓存，列表页给很多任务算当前值时不会重复查库。
 */
@Component
public class MetricEvaluator {

    private final ChannelCockpitService cockpitService;
    private final SubjectQueryRepository subjects;
    private final ReaderQueryRepository readers;

    public MetricEvaluator(ChannelCockpitService cockpitService, SubjectQueryRepository subjects, ReaderQueryRepository readers) {
        this.cockpitService = cockpitService;
        this.subjects = subjects;
        this.readers = readers;
    }

    public Context context() {
        return new Context();
    }

    public final class Context {
        private final Map<String, Object> cache = new HashMap<>();

        @SuppressWarnings("unchecked")
        private <T> T cached(String key, Supplier<T> loader) {
            return (T) cache.computeIfAbsent(key, k -> loader.get());
        }

        /** entityName 只用于部门；entityId 用于频道、账号、博主；全社区两者都不需要。 */
        public Double evaluate(GrowthEntityType type, Long entityId, String entityName, TaskMetric metric, LocalDate from,
                               LocalDate to) {
            return switch (type) {
                case CHANNEL -> channel(entityId, metric, from, to);
                case ACCOUNT -> subject(SubjectType.ACCOUNT, entityId, metric, from, to);
                case BLOGGER -> subject(SubjectType.BLOGGER, entityId, metric, from, to);
                case DEPARTMENT -> department(entityName, metric, from, to);
                case OVERALL -> overall(metric, from, to);
            };
        }

        private Double channel(Long id, TaskMetric metric, LocalDate from, LocalDate to) {
            List<ChannelSnapshot> snapshots = cached("channels:" + from + ":" + to, () -> cockpitService.snapshot(from, to));
            ChannelPeriod period = snapshots.stream().filter(s -> s.id() == id).map(ChannelSnapshot::period).findFirst().orElse(null);
            if (period == null || period.content() == null) {
                return null;
            }
            ContentMetrics content = period.content();
            SupplyMetrics supply = period.supply();
            return switch (metric) {
                case PV -> (double) content.pv();
                case OWN_ENTRY_SHARE -> content.ownEntryShare();
                case OWN_CTR -> period.distribution().ctr();
                case INTERACTION_RATE -> content.interactionRate();
                case POSTS -> (double) supply.contents();
                default -> null;
            };
        }

        private Double subject(SubjectType type, Long id, TaskMetric metric, LocalDate from, LocalDate to) {
            SubjectFilter filter = SubjectFilter.ofIds(id);
            String key = type + ":" + id + ":" + from + ":" + to;
            ContentMetrics content = cached("content:" + key, () -> ContentMetricsFactory.build(
                subjects.contentSide(type, filter, from, to).get(id), subjects.interactions(type, filter, from, to).getOrDefault(id, 0L),
                List.of()));
            return switch (metric) {
                case PV -> (double) content.pv();
                case INTERACTION_RATE -> content.interactionRate();
                case POSTS -> {
                    SupplyMetrics supply = cached("supply:" + key,
                        () -> subjects.supply(type, filter, from, to).getOrDefault(id, SupplyMetrics.EMPTY));
                    yield (double) supply.contents();
                }
                default -> null;
            };
        }

        private Double department(String dept, TaskMetric metric, LocalDate from, LocalDate to) {
            DeptPeriod period = period(dept, from, to);
            return switch (metric) {
                case PENETRATION -> period.penetration();
                case ACTIVE_USERS -> (double) period.active();
                default -> null;
            };
        }

        private Double overall(TaskMetric metric, LocalDate from, LocalDate to) {
            DeptPeriod period = period(null, from, to);
            return switch (metric) {
                case ACTIVE_USERS -> (double) period.active();
                case ACTIVE_RATE -> period.penetration();
                default -> null;
            };
        }

        /** dept 为空表示全社区。 */
        private DeptPeriod period(String dept, LocalDate from, LocalDate to) {
            List<Member> members = cached("members", readers::members);
            Map<Long, MemberActivity> activity = cached("activity:" + from + ":" + to, () -> {
                Map<Long, MemberActivity> map = new HashMap<>();
                readers.memberActivity(from, to).forEach(a -> map.put(a.employeeId(), a));
                return map;
            });
            List<Member> scope = dept == null ? members : members.stream()
                .filter(m -> dept.equals(m.dept() == null ? DepartmentPenetrationBuilder.UNASSIGNED : m.dept())).toList();
            return DepartmentPenetrationBuilder.period(scope, activity, from, to);
        }
    }
}
