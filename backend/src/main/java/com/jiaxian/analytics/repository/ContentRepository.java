package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.Content;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentRepository extends JpaRepository<Content, Long> {
}
