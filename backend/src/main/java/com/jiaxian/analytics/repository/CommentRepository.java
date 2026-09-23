package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.Comment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {
    List<Comment> findByParentCommentId(Long parentCommentId);
}
