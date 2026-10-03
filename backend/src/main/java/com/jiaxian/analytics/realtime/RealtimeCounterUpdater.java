package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.repository.ContentRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class RealtimeCounterUpdater {

    private static final String PV_KEY_PREFIX = "rt:pv:channel:";
    private static final String ENGAGEMENT_KEY_PREFIX = "rt:engagement:channel:";

    private final StringRedisTemplate redisTemplate;
    private final ContentRepository contentRepository;

    public RealtimeCounterUpdater(StringRedisTemplate redisTemplate, ContentRepository contentRepository) {
        this.redisTemplate = redisTemplate;
        this.contentRepository = contentRepository;
    }

    public void record(RealtimeEvent event) {
        String keyPrefix = switch (event.getEventType()) {
            case VIEW -> PV_KEY_PREFIX;
            case LIKE, SHARE, FAVORITE, COMMENT, COMMENT_LIKE -> ENGAGEMENT_KEY_PREFIX;
            case LOGIN -> null;
        };
        if (keyPrefix == null || event.getContentId() == null) {
            return;
        }

        contentRepository.findById(event.getContentId()).ifPresent(content ->
            increment(keyPrefix, content.getChannelId(), event.getOccurTime().toLocalDate()));
    }

    private void increment(String keyPrefix, Long channelId, LocalDate date) {
        String key = keyPrefix + channelId + ":" + date;
        Long value = redisTemplate.opsForValue().increment(key);
        if (value != null && value == 1L) {
            redisTemplate.expireAt(key, date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
        }
    }
}
