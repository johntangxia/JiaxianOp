package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.ActiveActionLog;
import com.jiaxian.analytics.domain.ActiveActionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ActiveActionLogRepository extends JpaRepository<ActiveActionLog, Long> {

    /** 每个员工从 since 起的行为次数（只含 actionType 这一类）：每行是 [员工 id, 次数]。 */
    @Query("SELECT a.employeeId, COUNT(a) FROM ActiveActionLog a WHERE a.actionType = :type AND a.occurTime >= :since"
        + " GROUP BY a.employeeId")
    List<Object[]> countByEmployeeSince(@Param("type") ActiveActionType type, @Param("since") LocalDateTime since);

    /** 这一类行为最近一次发生的时间。 */
    @Query("SELECT MAX(a.occurTime) FROM ActiveActionLog a WHERE a.actionType = :type")
    Optional<LocalDateTime> latestOccurTime(@Param("type") ActiveActionType type);
}
