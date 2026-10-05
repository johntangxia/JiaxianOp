package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.SearchLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SearchLogRepository extends JpaRepository<SearchLog, Long> {
}
