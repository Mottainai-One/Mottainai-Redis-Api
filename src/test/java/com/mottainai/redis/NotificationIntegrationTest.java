package com.mottainai.redis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@EnabledIfEnvironmentVariable(named = "REDIS_TEST_HOST", matches = ".+")
class NotificationIntegrationTest {
    private static final String SECRET = "integration_test_secret_at_least_32_characters";
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private long company;
    private NotificationWriter writer;
    private NotificationController controller;
    private NotificationStreamListener listener;
    private NotificationStreamMaintenance maintenance;
    private String stream;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(System.getenv("REDIS_TEST_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_TEST_PORT", "6379")));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void disconnect() { factory.destroy(); }

    @BeforeEach
    void setup() {
        company = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        writer = spy(new NotificationWriter(redis));
        controller = new NotificationController(redis, new AuthService(SECRET, "HS256"));
        stream = "test:notification:" + UUID.randomUUID();
        maintenance = new NotificationStreamMaintenance(redis, new com.fasterxml.jackson.databind.ObjectMapper(),
                stream, "writers", stream + ":dlq", 3, 604800, 2592000);
        listener = new NotificationStreamListener(redis, writer, maintenance, stream, "writers", "recovery", 1);
    }

    @AfterEach
    void cleanup() {
        listener.stop();
        redis.delete(stream);
        redis.delete(stream + ":dlq");
        var keys = redis.keys(NotificationKeys.PREFIX + "*:" + company + ":*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test
    void replayPreservesReadStatusContentTimestampTtlAndCount() {
        writer.write(company, 10, "one", "Original", "Body", 2);
        controller.markRead(token(company, 10), "one");
        String key = NotificationKeys.data(company, 10, "one");
        var original = redis.opsForHash().entries(key);
        redis.expire(key, Duration.ofSeconds(30));
        writer.write(company, 10, "one", "Changed", "Changed", 3);
        assertEquals(original, redis.opsForHash().entries(key));
        assertTrue(redis.getExpire(key) <= 30);
        var inbox = controller.list(token(company, 10), 20);
        assertEquals(0, inbox.unreadCount());
        assertEquals(1, inbox.notifications().size());
        assertEquals("read", inbox.notifications().get(0).status());
        assertEquals("read", controller.markRead(token(company, 10), "one").get("status"));
    }

    @Test
    void concurrentReplaysCreateOnlyOneNotification() throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
            for (int i = 0; i < 20; i++) tasks.add(() -> {
                writer.write(company, 10, "same", "Title", "Body", 2);
                return null;
            });
            for (var result : executor.invokeAll(tasks)) result.get();
        } finally {
            executor.shutdownNow();
        }
        var inbox = controller.list(token(company, 10), 20);
        assertEquals(1, inbox.notifications().size());
        assertEquals(1, inbox.unreadCount());
    }

    @Test
    void replayRepairsMissingIndexWithoutReopeningNotification() {
        writer.write(company, 10, "one", "Original", "Body", 2);
        controller.markRead(token(company, 10), "one");
        redis.delete(NotificationKeys.inbox(company, 10));
        writer.write(company, 10, "one", "Changed", "Changed", 3);
        var inbox = controller.list(token(company, 10), 20);
        assertEquals(1, inbox.notifications().size());
        assertEquals("Original", inbox.notifications().get(0).title());
        assertEquals("read", inbox.notifications().get(0).status());
        assertEquals(0, inbox.unreadCount());
    }

    @Test
    void expiredReferencesDoNotConsumeLimitOrInflateUnreadCount() throws Exception {
        writer.write(company, 10, "expired", "Gone", "Body", 3);
        writer.write(company, 10, "live", "Live", "Body", 1);
        String expired = NotificationKeys.data(company, 10, "expired");
        redis.expire(expired, Duration.ofMillis(1));
        Thread.sleep(20);
        assertFalse(redis.hasKey(expired));
        var inbox = controller.list(token(company, 10), 1);
        assertEquals(1, inbox.unreadCount());
        assertEquals("live", inbox.notifications().get(0).id());
        assertEquals(1L, redis.opsForZSet().size(NotificationKeys.inbox(company, 10)));
        assertEquals("1", redis.opsForValue().get(NotificationKeys.unread(company, 10)));
        controller.markRead(token(company, 10), "live");
        assertEquals(0, controller.list(token(company, 10), 20).unreadCount());
    }

    @Test
    void emptyInboxRemovesStaleCounterAndIndex() {
        writer.write(company, 10, "gone", "Gone", "Body", 1);
        redis.delete(NotificationKeys.data(company, 10, "gone"));
        var inbox = controller.list(token(company, 10), 20);
        assertTrue(inbox.notifications().isEmpty());
        assertEquals(0, inbox.unreadCount());
        assertFalse(redis.hasKey(NotificationKeys.unread(company, 10)));
        assertFalse(redis.hasKey(NotificationKeys.inbox(company, 10)));
    }

    @Test
    void httpEnforcesAuthenticationOwnershipLimitsAndPriorityOrder() throws Exception {
        writer.write(company, 10, "low", "Low", "Body", 1);
        writer.write(company, 10, "high", "High", "Body", 3);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/v1/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/notifications").header("Authorization", token(company, 10)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(2))
                .andExpect(jsonPath("$.notifications[0].id").value("high"));
        mvc.perform(get("/v1/notifications?limit=0").header("Authorization", token(company, 10)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/notifications").header("Authorization", token(company, 11)))
                .andExpect(jsonPath("$.unreadCount").value(0)).andExpect(jsonPath("$.notifications").isEmpty());
        mvc.perform(post("/v1/notifications/high/read").header("Authorization", token(company + 1, 10)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/v1/notifications/high/read").header("Authorization", token(company, 10)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("read"));
    }

    @Test
    void createsMissingStreamAndAcceptsOnlyExistingGroupError() {
        listener.createGroupIfMissing();
        listener.createGroupIfMissing();
        assertTrue(redis.hasKey(stream));
        assertEquals(0L, redis.opsForStream().size(stream));
    }

    @Test
    void recoversPendingMessageWithoutChangingAlreadyReadNotification() throws Exception {
        listener.createGroupIfMissing();
        var id = redis.opsForStream().add(notificationRecord());
        var delivered = redis.<String, String>opsForStream().read(Consumer.from("writers", "dead-consumer"),
                StreamOffset.create(stream, ReadOffset.lastConsumed())).get(0);
        String notificationId = "stream-" + id.getValue();
        // Simulate a crash between committing the write and acknowledging the delivery.
        writer.write(company, 10, notificationId, "Alert", "Body", 2);
        controller.markRead(token(company, 10), notificationId);
        Thread.sleep(20);
        listener.poll();
        assertEquals(0L, redis.opsForStream().pending(stream, "writers").getTotalPendingMessages());
        var inbox = controller.list(token(company, 10), 20);
        assertEquals(1, inbox.notifications().size());
        assertEquals("read", inbox.notifications().get(0).status());
        assertEquals(0, inbox.unreadCount());
        assertEquals(delivered.getId(), id);
    }

    @Test
    void writeFailureRemainsPendingAndNextPollRetriesIt() throws Exception {
        listener.createGroupIfMissing();
        redis.opsForStream().add(notificationRecord());
        doThrow(new IllegalStateException("temporary Redis failure")).doCallRealMethod()
                .when(writer).write(eq(company), eq(10L), anyString(), anyString(), anyString(), eq(2));
        listener.poll();
        assertEquals(1L, redis.opsForStream().pending(stream, "writers").getTotalPendingMessages());
        Thread.sleep(20);
        listener.poll();
        assertEquals(0L, redis.opsForStream().pending(stream, "writers").getTotalPendingMessages());
        assertEquals(1, controller.list(token(company, 10), 20).unreadCount());
    }

    @Test
    void pendingScanAdvancesPastAFullPageOfRecentDeliveries() {
        listener.stop();
        listener = new NotificationStreamListener(redis, writer, maintenance, stream, "writers", "recovery", 60000);
        listener.createGroupIfMissing();
        for (int i = 0; i < 101; i++) redis.opsForStream().add(notificationRecord());
        var messages = redis.<String, String>opsForStream().read(Consumer.from("writers", "old-consumer"),
                StreamReadOptions.empty().count(200), StreamOffset.create(stream, ReadOffset.lastConsumed()));
        var lastId = messages.get(100).getId();
        redis.execute(new DefaultRedisScript<>("""
                return #redis.call('XCLAIM', KEYS[1], ARGV[1], ARGV[2], 0, ARGV[3], 'IDLE', 60000, 'JUSTID')
                """, Long.class), java.util.List.of(stream), "writers", "old-consumer", lastId.getValue());
        listener.poll();
        assertEquals(101L, redis.opsForStream().pending(stream, "writers").getTotalPendingMessages());
        listener.poll();
        assertEquals(100L, redis.opsForStream().pending(stream, "writers").getTotalPendingMessages());
        assertEquals("stream-" + lastId.getValue(), controller.list(token(company, 10), 20).notifications().get(0).id());
    }

    @Test
    void malformedMessageIsAcknowledgedWithoutCreatingNotification() {
        listener.createGroupIfMissing();
        redis.opsForStream().add(MapRecord.create(stream, Map.of("company_id", "invalid")));
        listener.poll();
        assertEquals(0L, redis.opsForStream().pending(stream, "writers").getTotalPendingMessages());
        assertTrue(controller.list(token(company, 10), 20).notifications().isEmpty());
        var rejected = redis.<String, String>opsForStream().range(stream + ":dlq", org.springframework.data.domain.Range.unbounded());
        assertEquals(1, rejected.size());
        assertTrue(rejected.get(0).getValue().get("reason").startsWith("invalid_payload:"));
        assertTrue(rejected.get(0).getValue().get("payload").contains("invalid"));
    }

    @Test
    void createsAnEmptyDeadLetterStreamWithoutSyntheticRecords() {
        maintenance.initialize();
        maintenance.initialize();
        assertEquals(0L, redis.opsForStream().size(stream + ":dlq"));
        redis.delete(stream + ":dlq");
        redis.opsForValue().set(stream + ":dlq", "wrong type");
        assertThrows(RuntimeException.class, maintenance::initialize);
    }

    @Test
    void exhaustedDeliveriesMoveToDeadLetterExactlyOnce() throws Exception {
        listener.createGroupIfMissing();
        var id = redis.opsForStream().add(notificationRecord());
        doThrow(new IllegalStateException("permanent failure")).when(writer)
                .write(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyInt());
        for (int i = 0; i < 3; i++) { listener.poll(); Thread.sleep(20); }
        assertEquals(0L, redis.opsForStream().pending(stream, "writers").getTotalPendingMessages());
        var rejected = redis.<String, String>opsForStream().range(stream + ":dlq", org.springframework.data.domain.Range.unbounded());
        assertEquals(1, rejected.size());
        assertEquals(id.getValue(), rejected.get(0).getValue().get("source_id"));
        assertEquals("3", rejected.get(0).getValue().get("attempts"));
        maintenance.deadLetter(MapRecord.create(stream, notificationRecord().getValue()).withId(id), "repeat", 3);
        assertEquals(1L, redis.opsForStream().size(stream + ":dlq"));
    }

    @Test
    void failedDeadLetterWritePreservesOriginalPendingMessage() {
        listener.createGroupIfMissing();
        var id = redis.opsForStream().add(MapRecord.create(stream, Map.of("company_id", "invalid")));
        var message = redis.<String, String>opsForStream().read(Consumer.from("writers", "test"),
                StreamOffset.create(stream, ReadOffset.lastConsumed())).get(0);
        redis.opsForValue().set(stream + ":dlq", "wrong type");
        assertThrows(RuntimeException.class, () -> listener.onMessage(message));
        assertEquals(1L, redis.opsForStream().pending(stream, "writers").getTotalPendingMessages());
        redis.delete(stream + ":dlq");
        listener.onMessage(message);
        assertEquals(0L, redis.opsForStream().pending(stream, "writers").getTotalPendingMessages());
        assertEquals(id.getValue(), redis.<String, String>opsForStream().range(stream + ":dlq",
                org.springframework.data.domain.Range.unbounded()).get(0).getValue().get("source_id"));
    }

    @Test
    void retentionProtectsPendingAndUndeliveredEntriesInEveryGroup() {
        listener.createGroupIfMissing();
        var fields = notificationRecord().getValue();
        for (String id : java.util.List.of("1-0", "2-0", "3-0", "4-0"))
            redis.opsForStream().add(MapRecord.create(stream, fields).withId(org.springframework.data.redis.connection.stream.RecordId.of(id)));
        redis.<String, String>opsForStream().read(Consumer.from("writers", "reader"), StreamReadOptions.empty().count(3),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
        redis.opsForStream().acknowledge(stream, "writers", "1-0", "3-0");
        redis.opsForStream().createGroup(stream, ReadOffset.from("1-0"), "other-group");
        maintenance.trim();
        assertEquals(4L, redis.opsForStream().size(stream));
        redis.opsForStream().destroyGroup(stream, "other-group");
        maintenance.trim();
        assertEquals(java.util.List.of("2-0", "3-0", "4-0"), redis.<String, String>opsForStream().range(stream,
                org.springframework.data.domain.Range.unbounded()).stream().map(r -> r.getId().getValue()).toList());
        redis.opsForStream().acknowledge(stream, "writers", "2-0");
        maintenance.trim();
        assertEquals(java.util.List.of("3-0", "4-0"), redis.<String, String>opsForStream().range(stream,
                org.springframework.data.domain.Range.unbounded()).stream().map(r -> r.getId().getValue()).toList());
    }

    @Test
    void retentionRemovesOnlyExpiredAcknowledgedHistory() {
        listener.createGroupIfMissing();
        redis.opsForStream().add(MapRecord.create(stream, notificationRecord().getValue())
                .withId(org.springframework.data.redis.connection.stream.RecordId.of("1-0")));
        redis.opsForStream().add(notificationRecord());
        var messages = redis.<String, String>opsForStream().read(Consumer.from("writers", "reader"),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
        for (var message : messages) redis.opsForStream().acknowledge(stream, "writers", message.getId());
        maintenance.trim();
        assertEquals(1L, redis.opsForStream().size(stream));
        assertNotEquals("1-0", redis.<String, String>opsForStream().range(stream,
                org.springframework.data.domain.Range.unbounded()).get(0).getId().getValue());
    }

    private MapRecord<String, String, String> notificationRecord() {
        return MapRecord.create(stream, Map.of("company_id", Long.toString(company), "user_id", "10",
                "title", "Alert", "body", "Body", "priority", "2"));
    }

    private static String token(long company, long user) {
        return "Bearer " + Jwts.builder().subject(Long.toString(user)).claim("empresa_id", company)
                .claim("role", "ESTOQUISTA").expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }
}
