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
    private static final DefaultRedisScript<List> LIST = new DefaultRedisScript<>(NotificationScripts.RECONCILE + """
        local active, unread = reconcile(KEYS[1], KEYS[2], ARGV[1])
        local result = {tostring(unread)}
        for i = 1, math.min(#active, tonumber(ARGV[2])) do
            table.insert(result, redis.call('HGETALL', ARGV[1] .. active[i]))
        end
        return result
        """, List.class);

    private static final DefaultRedisScript<Long> MARK_AS_READ = new DefaultRedisScript<>(NotificationScripts.RECONCILE + """
        reconcile(KEYS[1], KEYS[3], ARGV[2])
        if redis.call('ZSCORE', KEYS[1], ARGV[1]) == false then return -1 end
        if redis.call('HGET', KEYS[2], 'status') == 'read' then return 2 end
        if redis.call('HGET', KEYS[2], 'status') ~= 'unread' then return -1 end
        redis.call('HSET', KEYS[2], 'status', 'read')
        reconcile(KEYS[1], KEYS[3], ARGV[2])
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
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Redis unavailable.");
        }
    }

    @GetMapping("/v1/notifications")
    public InboxResponse list(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                              @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 100.");
        AuthService.Principal principal = auth.principal(authorization);
        List<?> result = redis.execute(LIST, List.of(inboxKey(principal), unreadKey(principal)),
                notificationKey(principal, ""), String.valueOf(limit));
        if (result == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Redis unavailable.");
        List<NotificationResponse> notifications = new ArrayList<>();
        for (int i = 1; i < result.size(); i++) {
            List<?> fields = (List<?>) result.get(i);
            Map<Object, Object> data = new java.util.HashMap<>();
            for (int j = 0; j < fields.size(); j += 2) data.put(fields.get(j), fields.get(j + 1));
            notifications.add(new NotificationResponse(value(data, "id"), value(data, "title"), value(data, "body"),
                    Integer.parseInt(value(data, "priority")), value(data, "status"), Long.parseLong(value(data, "created_at"))));
        }
        return new InboxResponse(Integer.parseInt(result.get(0).toString()), notifications);
    }

    @PostMapping("/v1/notifications/{notificationId}/read")
    public Map<String, String> markRead(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                        @PathVariable String notificationId) {
        if (notificationId.isBlank() || notificationId.contains(":") || notificationId.length() > 128)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid identifier.");
        AuthService.Principal principal = auth.principal(authorization);
        Long result = redis.execute(MARK_AS_READ, List.of(inboxKey(principal), notificationKey(principal, notificationId), unreadKey(principal)),
                notificationId, notificationKey(principal, ""));
        if (result == null || result == -1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found.");
        return Map.of("id", notificationId, "status", "read");
    }

    private static String inboxKey(AuthService.Principal p) { return NotificationKeys.inbox(p.companyId(), p.userId()); }
    private static String unreadKey(AuthService.Principal p) { return NotificationKeys.unread(p.companyId(), p.userId()); }
    private static String notificationKey(AuthService.Principal p, String id) { return NotificationKeys.data(p.companyId(), p.userId(), id); }
    private static String value(Map<Object, Object> data, String key) { return data.get(key).toString(); }

    public record InboxResponse(int unreadCount, List<NotificationResponse> notifications) {}
    public record NotificationResponse(String id, String title, String body, int priority, String status, long createdAt) {}
}
