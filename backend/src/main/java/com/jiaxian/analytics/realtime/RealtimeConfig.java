package com.jiaxian.analytics.realtime;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class RealtimeConfig {

    public static final String COMMUNITY_EVENTS_TOPIC = "community-events";

    @Bean
    public NewTopic communityEventsTopic() {
        return TopicBuilder.name(COMMUNITY_EVENTS_TOPIC).partitions(3).replicas(1).build();
    }
}
