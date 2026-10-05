package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.Impression;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImpressionRepository extends JpaRepository<Impression, Long> {
}
