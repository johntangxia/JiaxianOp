package com.jiaxian.analytics.realtime;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RealtimeInfrastructureIT {

    @Autowired private KafkaAdmin kafkaAdmin;
    @Autowired private StringRedisTemplate redisTemplate;

    @Test
    void communityEventsTopicExistsWithThreePartitions() throws Exception {
        try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            TopicDescription description = adminClient
                .describeTopics(List.of(RealtimeConfig.COMMUNITY_EVENTS_TOPIC))
                .allTopicNames().get(30, TimeUnit.SECONDS)
                .get(RealtimeConfig.COMMUNITY_EVENTS_TOPIC);

            assertThat(description.partitions()).hasSize(3);
        }
    }

    @Test
    void redisRoundTripWorks() {
        String key = "rt:test:infrastructure-it";
        redisTemplate.opsForValue().set(key, "ok");
        try {
            assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("ok");
        } finally {
            redisTemplate.delete(key);
        }
    }
}
