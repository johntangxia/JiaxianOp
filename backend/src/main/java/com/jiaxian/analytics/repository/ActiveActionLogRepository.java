package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.ActiveActionLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActiveActionLogRepository extends JpaRepository<ActiveActionLog, Long> {
}
