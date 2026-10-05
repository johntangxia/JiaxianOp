package com.jiaxian.analytics.growth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface GrowthAlertRepository extends JpaRepository<GrowthAlert, Long> {

    Optional<GrowthAlert> findByKindAndEntityTypeAndEntityNameAndPeriodEnd(String kind, GrowthEntityType entityType,
                                                                           String entityName, LocalDate periodEnd);
}
