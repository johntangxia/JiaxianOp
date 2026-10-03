package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.PointsLedger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PointsLedgerRepository extends JpaRepository<PointsLedger, Long> {
    List<PointsLedger> findByEmployeeId(Long employeeId);

    List<PointsLedger> findBySimulationBatchId(String simulationBatchId);
}
