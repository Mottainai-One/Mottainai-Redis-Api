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
            if ARGV[9] ~= '' then
                redis.call('HSET', KEYS[1], 'promotion_id', ARGV[9], 'store_id', ARGV[10], 'product_id', ARGV[11], 'expires_at', ARGV[12])
            end
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
        write(companyId,userId,notificationId,title,body,priority,null);
    }

    /** Grava uma oferta válida, limita o TTL ao fim da campanha e preserva a idempotência do aviso. */
    public void write(long companyId, long userId, String notificationId, String title, String body, int priority,
                      PromotionNotificationTarget target) {
        long now = Instant.now().toEpochMilli();
        long createdAt = now / 1000;
        if (target != null && target.expiresAt() <= now) return;
        long ttl = target == null ? NotificationKeys.TTL_SECONDS
                : Math.min(NotificationKeys.TTL_SECONDS, Math.max(1, (target.expiresAt()-now+999)/1000));
        List<String> keys = List.of(
                NotificationKeys.data(companyId, userId, notificationId),
                NotificationKeys.inbox(companyId, userId),
                NotificationKeys.unread(companyId, userId));
        redis.execute(WRITE, keys, notificationId, title, body, String.valueOf(priority),
                String.valueOf(createdAt), String.valueOf(ttl),
                String.valueOf(NotificationKeys.PRIORITY_WEIGHT),
                NotificationKeys.data(companyId, userId, ""),
                target == null ? "" : Long.toString(target.promotionId()),
                target == null ? "" : Long.toString(target.storeId()),
                target == null ? "" : Long.toString(target.productId()),
                target == null ? "" : Long.toString(target.expiresAt()));
    }
}
