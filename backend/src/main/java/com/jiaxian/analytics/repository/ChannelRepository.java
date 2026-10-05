package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.Channel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChannelRepository extends JpaRepository<Channel, Long> {

    List<Channel> findByCategory(String category);

    void deleteByCategory(String category);
}
