package com.mottainai.redis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class NotificationStreamListenerTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final NotificationWriter writer = mock(NotificationWriter.class);
    private final StreamOperations<String, String, String> streams = mock(StreamOperations.class);
    private final NotificationStreamListener listener =
            new NotificationStreamListener(redis, writer, "stream", "group", "consumer", 60000);

    @AfterEach
    void stop() { listener.stop(); }

    @Test
    void propagatesGroupCreationFailures() {
        var failure = new IllegalStateException("Redis unavailable");
        when(redis.execute(any(RedisCallback.class))).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, listener::createGroupIfMissing));
    }

    @Test
    void toleratesOnlyBusyGroupIncludingWrappedRedisErrors() {
        when(redis.execute(any(RedisCallback.class))).thenThrow(
                new IllegalStateException("command failed", new IllegalStateException("BUSYGROUP Consumer Group name already exists")));
        assertDoesNotThrow(listener::createGroupIfMissing);
    }

    @ParameterizedTest
    @ValueSource(strings = {"company_id", "user_id", "title", "body", "priority", "id"})
    void acknowledgesInvalidFieldsWithoutWriting(String field) {
        when(redis.<String, String>opsForStream()).thenReturn(streams);
        Map<String, String> fields = new HashMap<>(Map.of("company_id", "1", "user_id", "10",
                "title", "Title", "body", "Body", "priority", "2", "id", "valid"));
        fields.put(field, switch (field) {
            case "id" -> "invalid:id";
            case "title", "body" -> " ";
            default -> "0";
        });
        var message = MapRecord.create("stream", fields).withId(RecordId.of("1-0"));
        listener.onMessage(message);
        verifyNoInteractions(writer);
        verify(streams).acknowledge("stream", "group", message.getId());
    }

    @Test
    void doesNotAcknowledgeFailedWrites() {
        when(redis.<String, String>opsForStream()).thenReturn(streams);
        doThrow(new IllegalStateException("write unavailable")).when(writer)
                .write(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyInt());
        var message = MapRecord.create("stream", Map.of("company_id", "1", "user_id", "10",
                "title", "Title", "body", "Body", "priority", "2")).withId(RecordId.of("1-0"));
        assertThrows(IllegalStateException.class, () -> listener.onMessage(message));
        verifyNoInteractions(streams);
    }
}
