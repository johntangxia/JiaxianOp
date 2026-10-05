package com.jiaxian.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守住测试隔离：测试必须连独立的数据库、Kafka 主题、消费组和 Redis 库，
 * 否则回填类测试会清空演示数据，实时流水线测试会和运行中的后端容器互相消费。
 */
@SpringBootTest
class TestIsolationIT {

    @Autowired private JdbcTemplate jdbc;
    @Value("${spring.datasource.url}") private String url;
    @Value("${realtime.topic}") private String topic;
    @Value("${spring.kafka.consumer.group-id}") private String group;
    @Value("${spring.data.redis.database}") private int redisDatabase;

    @Test
    void testsUseTheirOwnDatabaseTopicConsumerGroupAndRedisDatabase() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("jiaxian_test");
        assertThat(url).endsWith("/jiaxian_test");
        assertThat(topic).isNotEqualTo("community-events").endsWith("-test");
        assertThat(group).isNotEqualTo("jiaxian-analytics-realtime").endsWith("-test");
        assertThat(redisDatabase).isNotZero();
    }
}
