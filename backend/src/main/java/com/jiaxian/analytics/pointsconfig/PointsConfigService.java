package com.jiaxian.analytics.pointsconfig;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 积分权重按生效日期版本化。引擎对每个动作类型取生效日期最大的版本（不看事件发生时间），
 * 所以"修改"只能新增/覆盖一个今天生效的版本：未来日期会立刻生效，过去日期又不会生效。
 */
@Service
public class PointsConfigService {

    static final int MIN_WEIGHT = 0;
    static final int MAX_WEIGHT = 10000;

    private final PointsConfigRepository repository;
    private final Clock clock;

    public PointsConfigService(PointsConfigRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 全部动作类型：主动在前，其次按当前权重降序，再按动作类型。 */
    public List<PointsConfigItem> list() {
        Map<String, List<PointsConfigRepository.Row>> byAction = repository.all().stream()
            .collect(Collectors.groupingBy(PointsConfigRepository.Row::actionType, LinkedHashMap::new,
                Collectors.toList()));
        return byAction.entrySet().stream()
            .map(entry -> toItem(entry.getKey(), entry.getValue()))
            .sorted(Comparator
                .comparing((PointsConfigItem item) -> "ACTIVE".equals(item.category()) ? 0 : 1)
                .thenComparing(item -> -item.current().weight())
                .thenComparing(PointsConfigItem::actionType))
            .toList();
    }

    @Transactional
    public PointsConfigItem update(String actionType, Integer weight) {
        if (weight == null || weight < MIN_WEIGHT || weight > MAX_WEIGHT) {
            throw new InvalidPointsConfigException(
                "weight must be an integer between " + MIN_WEIGHT + " and " + MAX_WEIGHT);
        }
        String category = repository.categoryOf(actionType).orElseThrow(
            () -> new PointsConfigNotFoundException("Unknown action type '" + actionType + "'"));
        LocalDate today = LocalDate.now(clock);
        Optional<PointsConfigRepository.Row> existing = repository.findByDate(actionType, today);
        if (existing.isPresent()) {
            repository.updateWeight(existing.get().id(), weight);
        } else {
            repository.insert(actionType, category, weight, today);
        }
        return list().stream().filter(item -> item.actionType().equals(actionType)).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteVersion(long id) {
        PointsConfigRepository.Row row = repository.find(id).orElseThrow(
            () -> new PointsConfigNotFoundException("Weight version " + id + " does not exist"));
        if (repository.countVersions(row.actionType()) <= 1) {
            throw new PointsConfigConflictException(
                "Cannot delete the only version of " + row.actionType());
        }
        repository.delete(id);
    }

    private static PointsConfigItem toItem(String actionType, List<PointsConfigRepository.Row> rows) {
        List<WeightVersion> versions = rows.stream()
            .map(row -> new WeightVersion(row.id(), row.weight(), row.effectiveDate())).toList();
        return new PointsConfigItem(actionType, rows.get(0).category(), versions.get(0), versions);
    }
}
