package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.PointsWeightConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PointsWeightConfigRepository extends JpaRepository<PointsWeightConfig, Long> {

    // Spring Data's derived "findFirstBy...OrderBy..." query paginates via a bind-parameterized
    // "FETCH FIRST ? ROWS ONLY" clause, which OpenGauss's parser rejects with a syntax error
    // ("syntax error at or near "$2"") — it only accepts a literal row count there, not a bind
    // parameter. Using a native query with a hardcoded "LIMIT 1" avoids that clause entirely.
    @Query(value = "SELECT * FROM points_weight_config WHERE action_type = :actionType "
        + "ORDER BY effective_date DESC LIMIT 1", nativeQuery = true)
    Optional<PointsWeightConfig> findFirstByActionTypeOrderByEffectiveDateDesc(@Param("actionType") String actionType);
}
