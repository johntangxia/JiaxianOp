package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.ContentTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContentTagRepository extends JpaRepository<ContentTag, Long> {
    List<ContentTag> findByContentId(Long contentId);
}
