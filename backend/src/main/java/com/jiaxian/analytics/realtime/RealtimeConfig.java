package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.repository.ActiveActionLogRepository;
import com.jiaxian.analytics.repository.CommentRepository;
import com.jiaxian.analytics.repository.ContentAuthorRepository;
import com.jiaxian.analytics.repository.EngagementLogRepository;
import com.jiaxian.analytics.repository.PointsLedgerRepository;
import com.jiaxian.analytics.repository.PointsWeightConfigRepository;
import com.jiaxian.analytics.repository.ContentTagRepository;
import com.jiaxian.analytics.repository.FollowRelationRepository;
import com.jiaxian.analytics.repository.ImpressionRepository;
import com.jiaxian.analytics.repository.PvLogRepository;
import com.jiaxian.analytics.repository.SearchLogRepository;
import com.jiaxian.analytics.simulator.BehaviorEventGenerator;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import java.util.Random;

@Configuration
public class RealtimeConfig {

    /** 默认的事件主题名；用 realtime.topic 覆盖（测试用独立的主题，避免和演示环境的后端互相消费）。 */
    public static final String COMMUNITY_EVENTS_TOPIC = "community-events";

    @Bean
    public NewTopic communityEventsTopic(@Value("${realtime.topic:" + COMMUNITY_EVENTS_TOPIC + "}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    /** 复用 Plan 1 的积分引擎；它本身没有可变状态，可以作为单例在消费线程里共用。 */
    @Bean
    public BehaviorEventGenerator behaviorEventGenerator(PvLogRepository pvLogRepository,
                                                         EngagementLogRepository engagementLogRepository,
                                                         ActiveActionLogRepository activeActionLogRepository,
                                                         ContentAuthorRepository contentAuthorRepository,
                                                         PointsWeightConfigRepository pointsWeightConfigRepository,
                                                         PointsLedgerRepository pointsLedgerRepository,
                                                         CommentRepository commentRepository,
                                                         ImpressionRepository impressionRepository,
                                                         FollowRelationRepository followRelationRepository,
                                                         SearchLogRepository searchLogRepository,
                                                         ContentTagRepository contentTagRepository) {
        return new BehaviorEventGenerator(
            pvLogRepository, engagementLogRepository, activeActionLogRepository,
            contentAuthorRepository, pointsWeightConfigRepository, pointsLedgerRepository,
            commentRepository, impressionRepository, followRelationRepository, searchLogRepository,
            contentTagRepository, new Random());
    }
}
