package com.mottainai.redis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class NotificationController {
    private static final String PREFIX = "mottainai:v1:notification:";
    private static final DefaultRedisScript<Long> MARK_AS_READ = new DefaultRedisScript<>("""
        if redis.call('ZSCORE', KEYS[1], ARGV[1]) == false then return -1 end
        if redis.call('HGET', KEYS[2], 'status') == 'read' then return 2 end
        if redis.call('HGET', KEYS[2], 'status') ~= 'unread' then return -1 end
        redis.call('HSET', KEYS[2], 'status', 'read')
        local current = tonumber(redis.call('GET', KEYS[3]) or '0')
        if current > 0 then redis.call('DECR', KEYS[3]) end
        return 1
        """, Long.class);

    private final StringRedisTemplate redis;
    private final AuthService auth;

    public NotificationController(StringRedisTemplate redis, AuthService auth) {
        this.redis = redis;
        this.auth = auth;
    }

    @GetMapping("/livez")
    public Map<String, String> livez() { return Map.of("status", "alive"); }

    @GetMapping("/readyz")
    public Map<String, String> readyz() {
        try (RedisConnection connection = redis.getRequiredConnectionFactory().getConnection()) {
            if (!"PONG".equals(connection.ping())) throw new IllegalStateException();
            return Map.of("status", "ready");
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Redis indisponível.");
        }
    }

    @GetMapping("/v1/notifications")
    public InboxResponse list(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                              @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit deve estar entre 1 e 100.");
        AuthService.Principal principal = auth.principal(authorization);
        var ids = redis.opsForZSet().reverseRange(inboxKey(principal), 0, limit - 1);
        List<NotificationResponse> notifications = new ArrayList<>();
        if (ids != null) for (String id : ids) {
            Map<Object, Object> data = redis.opsForHash().entries(notificationKey(principal, id));
            if (!data.isEmpty()) notifications.add(new NotificationResponse(id, value(data, "title"), value(data, "body"),
                    Integer.parseInt(value(data, "priority")), value(data, "status"), Long.parseLong(value(data, "created_at"))));
        }
        String unread = redis.opsForValue().get(unreadKey(principal));
        return new InboxResponse(unread == null ? 0 : Integer.parseInt(unread), notifications);
    }

    @PostMapping("/v1/notifications/{notificationId}/read")
    public Map<String, String> markRead(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                        @PathVariable String notificationId) {
        if (notificationId.isBlank() || notificationId.contains(":") || notificationId.length() > 128)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Identificador inválido.");
        AuthService.Principal principal = auth.principal(authorization);
        Long result = redis.execute(MARK_AS_READ, List.of(inboxKey(principal), notificationKey(principal, notificationId), unreadKey(principal)), notificationId);
        if (result == null || result == -1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notificação não encontrada.");
        return Map.of("id", notificationId, "status", "read");
    }

    private static String inboxKey(AuthService.Principal p) { return PREFIX + "inbox:" + p.empresaId() + ":" + p.usuarioId(); }
    private static String unreadKey(AuthService.Principal p) { return PREFIX + "unread:" + p.empresaId() + ":" + p.usuarioId(); }
    private static String notificationKey(AuthService.Principal p, String id) { return PREFIX + "data:" + p.empresaId() + ":" + p.usuarioId() + ":" + id; }
    private static String value(Map<Object, Object> data, String key) { return data.get(key).toString(); }

    public record InboxResponse(int unreadCount, List<NotificationResponse> notifications) {}
    public record NotificationResponse(String id, String title, String body, int priority, String status, long createdAt) {}
}
