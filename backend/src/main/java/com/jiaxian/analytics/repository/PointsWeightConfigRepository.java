package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.PointsWeightConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PointsWeightConfigRepository extends JpaRepository<PointsWeightConfig, Long> {
    Optional<PointsWeightConfig> findFirstByActionTypeOrderByEffectiveDateDesc(String actionType);
}
