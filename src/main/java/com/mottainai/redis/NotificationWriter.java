package com.mottainai.redis;

import java.time.Instant;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class NotificationWriter {
    // Hash, inbox and unread counter move together so a partial failure cannot leave a
    // notification that is visible but uncounted. Re-writing the same id is idempotent.
    private static final DefaultRedisScript<Long> WRITE = new DefaultRedisScript<>("""
        local is_new = redis.call('EXISTS', KEYS[1]) == 0
        redis.call('HSET', KEYS[1], 'id', ARGV[1], 'title', ARGV[2], 'body', ARGV[3], 'priority', ARGV[4], 'status', 'unread', 'created_at', ARGV[5])
        redis.call('EXPIRE', KEYS[1], ARGV[6])
        redis.call('ZADD', KEYS[2], ARGV[7], ARGV[1])
        redis.call('EXPIRE', KEYS[2], ARGV[6])
        if is_new then redis.call('INCR', KEYS[3]) end
        redis.call('EXPIRE', KEYS[3], ARGV[6])
        return tonumber(redis.call('GET', KEYS[3]) or '0')
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
                String.valueOf(NotificationKeys.score(priority, createdAt)));
    }
}
