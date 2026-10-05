package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.FollowRelation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FollowRelationRepository extends JpaRepository<FollowRelation, Long> {
    List<FollowRelation> findByAccountId(Long accountId);

    List<FollowRelation> findByFollowerEmployeeId(Long followerEmployeeId);
}
