package com.mottainai.redis;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

/**
 * Consumes notifications produced by the Mottainai AI service and writes them into the
 * inbox structure the mobile app reads. Kept off HTTP on purpose: only producers that can
 * reach Redis inside the Docker network can publish, so a mobile client cannot forge alerts.
 */
@Component
public class NotificationStreamListener implements StreamListener<String, MapRecord<String, String, String>> {
    private static final Logger log = LoggerFactory.getLogger(NotificationStreamListener.class);

    private final StringRedisTemplate redis;
    private final NotificationWriter writer;
    private final String streamKey;
    private final String group;
    private final String consumer;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final Duration reclaimIdle;
    private String pendingCursor;

    public NotificationStreamListener(StringRedisTemplate redis,
                                      NotificationWriter writer,
                                      @Value("${mottainai.stream.key}") String streamKey,
                                      @Value("${mottainai.stream.group}") String group,
                                      @Value("${mottainai.stream.consumer}") String consumer,
                                      @Value("${mottainai.stream.reclaim-idle-ms:60000}") long reclaimIdleMillis) {
        this.redis = redis;
        this.writer = writer;
        this.streamKey = streamKey;
        this.group = group;
        this.consumer = consumer;
        if (reclaimIdleMillis < 1) throw new IllegalArgumentException("reclaim-idle-ms must be positive");
        this.reclaimIdle = Duration.ofMillis(reclaimIdleMillis);
    }

    @PostConstruct
    void start() {
        createGroupIfMissing();
        executor.scheduleWithFixedDelay(this::poll, 0, 1, TimeUnit.SECONDS);
        log.info("Listening on stream {} as {}/{}", streamKey, group, consumer);
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    void createGroupIfMissing() {
        try {
            redis.execute((RedisCallback<String>) connection -> connection.streamCommands().xGroupCreate(
                    redis.getStringSerializer().serialize(streamKey), group, ReadOffset.from("0-0"), true));
        } catch (RuntimeException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause.getMessage() != null && cause.getMessage().startsWith("BUSYGROUP ")) return;
            }
            throw exception;
        }
    }

    // Scan bounded pages, advancing the cursor even when recent entries cannot yet be
    // claimed. This prevents a busy first page from starving older failed deliveries.
    void poll() {
        try {
            Range<String> range = pendingCursor == null ? Range.unbounded()
                    : Range.of(Range.Bound.exclusive(pendingCursor), Range.Bound.unbounded());
            var pending = redis.opsForStream().pending(streamKey, group, range, 100);
            if (pending != null) {
                for (var entry : pending) {
                    pendingCursor = entry.getIdAsString();
                    if (entry.getElapsedTimeSinceLastDelivery().compareTo(reclaimIdle) >= 0) {
                        var claimed = redis.<String, String>opsForStream().claim(streamKey, group, consumer,
                                reclaimIdle, RecordId.of(entry.getIdAsString()));
                        for (var message : claimed) process(message);
                    }
                }
                if (pending.size() < 100) pendingCursor = null;
            }
            var messages = redis.<String, String>opsForStream().read(Consumer.from(group, consumer),
                    StreamReadOptions.empty().count(100), StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
            if (messages != null) for (var message : messages) process(message);
        } catch (RuntimeException exception) {
            log.error("Notification stream poll failed; retrying on the next poll", exception);
        }
    }

    private void process(MapRecord<String, String, String> message) {
        try {
            onMessage(message);
        } catch (RuntimeException exception) {
            log.error("Notification stream message {} remains pending for retry", message.getId(), exception);
        }
    }

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        Map<String, String> fields = message.getValue();
        long companyId;
        long userId;
        String title;
        String body;
        int priority;
        String notificationId;
        try {
            companyId = positive(fields.get("company_id"), "company_id");
            userId = positive(fields.get("user_id"), "user_id");
            title = required(fields.get("title"), "title", 120);
            body = required(fields.get("body"), "body", 1000);
            priority = priority(fields.get("priority"));
            notificationId = notificationId(fields.get("id"), message.getId().getValue());
        } catch (IllegalArgumentException exception) {
            // A malformed message never becomes valid, so acknowledge it instead of
            // redelivering it forever. Bad data here would break the read path.
            log.warn("Discarding malformed stream message {}: {}", message.getId(), exception.getMessage());
            redis.opsForStream().acknowledge(streamKey, group, message.getId());
            return;
        }
        writer.write(companyId, userId, notificationId, title, body, priority);
        log.info("Notification {} written for company {} user {}", notificationId, companyId, userId);
        redis.opsForStream().acknowledge(streamKey, group, message.getId());
    }

    private static long positive(String raw, String field) {
        long value;
        try {
            value = Long.parseLong(required(raw, field, 20));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        if (value < 1) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }

    private static int priority(String raw) {
        int value;
        try {
            value = Integer.parseInt(required(raw, "priority", 2));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("priority must be a number");
        }
        if (value < 1 || value > 3) throw new IllegalArgumentException("priority must be between 1 and 3");
        return value;
    }

    private static String notificationId(String raw, String streamId) {
        if (raw == null || raw.isBlank()) return "stream-" + streamId;
        String value = raw.trim();
        if (value.contains(":") || value.length() > 128) throw new IllegalArgumentException("invalid id");
        return value;
    }

    private static String required(String raw, String field, int maxLength) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException(field + " is required");
        String value = raw.trim();
        if (value.length() > maxLength) throw new IllegalArgumentException(field + " is longer than " + maxLength);
        return value;
    }
}
