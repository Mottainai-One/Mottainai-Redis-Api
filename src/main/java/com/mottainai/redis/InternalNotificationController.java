package com.mottainai.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Server-to-server notification ingress. Mobile clients must continue using the read-only API. */
@RestController
@RequestMapping("/internal/v1/notifications")
public class InternalNotificationController {
    private final StringRedisTemplate redis;
    private final String streamKey;
    private final String publisherSecret;

    public InternalNotificationController(StringRedisTemplate redis,
            @Value("${mottainai.stream.key}") String streamKey,
            @Value("${mottainai.notification.publisher-secret:}") String publisherSecret) {
        this.redis = redis;
        this.streamKey = streamKey;
        this.publisherSecret = publisherSecret;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> publish(
            @RequestHeader(value = "X-Mottainai-Notification-Secret", required = false) String suppliedSecret,
            @RequestBody PublishRequest request) {
        if (publisherSecret.length() < 32) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Notification publisher is not configured.");
        }
        if (suppliedSecret == null || !MessageDigest.isEqual(
                publisherSecret.getBytes(StandardCharsets.UTF_8), suppliedSecret.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid publisher credentials.");
        }
        if (request == null || request.companyId() < 1 || request.userId() < 1
                || request.id() == null || request.id().isBlank() || request.id().length() > 128
                || request.id().contains(":")
                || request.title() == null || request.title().isBlank() || request.title().length() > 120
                || request.body() == null || request.body().isBlank() || request.body().length() > 1000
                || request.priority() < 1 || request.priority() > 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid notification payload.");
        }

        redis.opsForStream().add(streamKey, Map.of(
                "company_id", Long.toString(request.companyId()),
                "user_id", Long.toString(request.userId()),
                "id", request.id(),
                "title", request.title(),
                "body", request.body(),
                "priority", Integer.toString(request.priority())));
        return ResponseEntity.accepted().body(Map.of("id", request.id(), "status", "queued"));
    }

    public record PublishRequest(long companyId, long userId, String id, String title, String body, int priority) {}
}
