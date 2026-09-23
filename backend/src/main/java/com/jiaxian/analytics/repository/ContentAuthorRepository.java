package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.ContentAuthor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContentAuthorRepository extends JpaRepository<ContentAuthor, Long> {
    List<ContentAuthor> findByContentId(Long contentId);
}
