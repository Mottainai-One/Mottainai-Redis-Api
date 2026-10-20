package com.mottainai.redis;

import java.time.Instant;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class NotificationWriter {
    // Serialize creation and reconciliation with readers. Replaying a live ID preserves
    // its payload, read status, timestamp and expiry.
    private static final DefaultRedisScript<Long> WRITE = new DefaultRedisScript<>(NotificationScripts.RECONCILE + """
        if redis.call('EXISTS', KEYS[1]) == 0 then
            redis.call('HSET', KEYS[1], 'id', ARGV[1], 'title', ARGV[2], 'body', ARGV[3], 'priority', ARGV[4], 'status', 'unread', 'created_at', ARGV[5])
            redis.call('EXPIRE', KEYS[1], ARGV[6])
        end
        local priority = tonumber(redis.call('HGET', KEYS[1], 'priority'))
        local created = tonumber(redis.call('HGET', KEYS[1], 'created_at'))
        redis.call('ZADD', KEYS[2], 'NX', priority * tonumber(ARGV[7]) + created, ARGV[1])
        local active, unread = reconcile(KEYS[2], KEYS[3], ARGV[8])
        return unread
        """, Long.class);

    private final StringRedisTemplate redis;

    public NotificationWriter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void write(long companyId, long userId, String notificationId, String title, String body, int priority) {
        long createdAt = Instant.now().getEpochSecond();
        List<String> keys = List.of(
                NotificationKeys.data(companyId, userId, notificationId),
                NotificationKeys.inbox(companyId, userId),
                NotificationKeys.unread(companyId, userId));
        redis.execute(WRITE, keys, notificationId, title, body, String.valueOf(priority),
                String.valueOf(createdAt), String.valueOf(NotificationKeys.TTL_SECONDS),
                String.valueOf(NotificationKeys.PRIORITY_WEIGHT),
                NotificationKeys.data(companyId, userId, ""));
    }
}
