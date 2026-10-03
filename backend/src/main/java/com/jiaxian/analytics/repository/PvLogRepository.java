package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.PvLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PvLogRepository extends JpaRepository<PvLog, Long> {
    List<PvLog> findByContentId(Long contentId);

    long countBySimulationBatchId(String simulationBatchId);
}
