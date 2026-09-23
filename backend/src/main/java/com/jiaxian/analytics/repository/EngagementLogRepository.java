package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.EngagementLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EngagementLogRepository extends JpaRepository<EngagementLog, Long> {
    List<EngagementLog> findByContentId(Long contentId);
    List<EngagementLog> findByCommentId(Long commentId);
}
