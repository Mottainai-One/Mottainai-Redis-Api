package com.mottainai.redis;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
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
    private final RedisConnectionFactory connectionFactory;
    private final NotificationWriter writer;
    private final String streamKey;
    private final String group;
    private final String consumer;

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;

    public NotificationStreamListener(StringRedisTemplate redis,
                                      RedisConnectionFactory connectionFactory,
                                      NotificationWriter writer,
                                      @Value("${mottainai.stream.key}") String streamKey,
                                      @Value("${mottainai.stream.group}") String group,
                                      @Value("${mottainai.stream.consumer}") String consumer) {
        this.redis = redis;
        this.connectionFactory = connectionFactory;
        this.writer = writer;
        this.streamKey = streamKey;
        this.group = group;
        this.consumer = consumer;
    }

    @PostConstruct
    void start() {
        createGroupIfMissing();
        // Must stay below spring.data.redis.timeout, otherwise the blocking read races the
        // client command timeout and every poll fails.
        var options = StreamMessageListenerContainer
                .StreamMessageListenerContainerOptions.builder()
                .pollTimeout(Duration.ofSeconds(1))
                .serializer(RedisSerializer.string())
                .build();
        container = StreamMessageListenerContainer.create(connectionFactory, options);
        container.receive(Consumer.from(group, consumer),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()), this);
        container.start();
        log.info("Listening on stream {} as {}/{}", streamKey, group, consumer);
    }

    @PreDestroy
    void stop() {
        if (container != null) container.stop();
    }

    private void createGroupIfMissing() {
        try {
            redis.opsForStream().createGroup(streamKey, ReadOffset.from("0-0"), group);
        } catch (RuntimeException exception) {
            log.debug("Consumer group {} already exists on {}", group, streamKey);
        }
    }

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        Map<String, String> fields = message.getValue();
        try {
            long companyId = positive(fields.get("company_id"), "company_id");
            long userId = positive(fields.get("user_id"), "user_id");
            String title = required(fields.get("title"), "title", 120);
            String body = required(fields.get("body"), "body", 1000);
            int priority = priority(fields.get("priority"));
            String notificationId = notificationId(fields.get("id"));

            writer.write(companyId, userId, notificationId, title, body, priority);
            log.info("Notification {} written for company {} user {}", notificationId, companyId, userId);
        } catch (IllegalArgumentException exception) {
            // A malformed message never becomes valid, so acknowledge it instead of
            // redelivering it forever. Bad data here would break the read path.
            log.warn("Discarding malformed stream message {}: {}", message.getId(), exception.getMessage());
        }
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

    private static String notificationId(String raw) {
        if (raw == null || raw.isBlank()) return UUID.randomUUID().toString().replace("-", "");
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
