package com.mottainai.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class NotificationStreamMaintenance {
    private static final DefaultRedisScript<Long> INITIALIZE = new DefaultRedisScript<>("""
        local kind = redis.call('TYPE', KEYS[1]).ok
        if kind ~= 'none' and kind ~= 'stream' then return redis.error_reply('Dead-letter key must be a stream') end
        if kind == 'none' then
            local id = redis.call('XADD', KEYS[1], '*', '_initialize', '1')
            redis.call('XDEL', KEYS[1], id)
        end
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> DEAD_LETTER = new DefaultRedisScript<>("""
        local pending = redis.call('XPENDING', KEYS[1], ARGV[1], ARGV[2], ARGV[2], 1)
        if #pending == 0 then return 0 end
        redis.call('XADD', KEYS[2], '*', 'source_stream', KEYS[1], 'source_id', ARGV[2],
            'consumer_group', ARGV[1], 'reason', ARGV[3], 'attempts', ARGV[4],
            'failed_at', ARGV[5], 'payload', ARGV[6])
        return redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])
        """, Long.class);

    private static final DefaultRedisScript<Long> TRIM = new DefaultRedisScript<>("""
        if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
        local function earlier(a, b)
            local am, as = string.match(a, '(%d+)%-(%d+)')
            local bm, bs = string.match(b, '(%d+)%-(%d+)')
            return tonumber(am) < tonumber(bm) or (tonumber(am) == tonumber(bm) and tonumber(as) < tonumber(bs))
        end
        local boundary = ARGV[1]
        for _, group in ipairs(redis.call('XINFO', 'GROUPS', KEYS[1])) do
            local name, delivered
            for i = 1, #group, 2 do
                if group[i] == 'name' then name = group[i + 1] end
                if group[i] == 'last-delivered-id' then delivered = group[i + 1] end
            end
            if earlier(delivered, boundary) then boundary = delivered end
            local pending = redis.call('XPENDING', KEYS[1], name)
            if pending[1] > 0 and earlier(pending[2], boundary) then boundary = pending[2] end
        end
        return redis.call('XTRIM', KEYS[1], 'MINID', boundary)
        """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final String streamKey;
    private final String group;
    private final String deadLetterKey;
    private final int maxDeliveries;
    private final long retentionSeconds;
    private final long deadLetterRetentionSeconds;

    public NotificationStreamMaintenance(StringRedisTemplate redis, ObjectMapper mapper,
            @Value("${mottainai.stream.key}") String streamKey,
            @Value("${mottainai.stream.group}") String group,
            @Value("${mottainai.stream.dead-letter-key:mottainai:v1:notification:dead-letter}") String deadLetterKey,
            @Value("${mottainai.stream.max-deliveries:5}") int maxDeliveries,
            @Value("${mottainai.stream.retention-seconds:604800}") long retentionSeconds,
            @Value("${mottainai.stream.dead-letter-retention-seconds:2592000}") long deadLetterRetentionSeconds) {
        if (maxDeliveries < 1 || retentionSeconds < 1 || deadLetterRetentionSeconds < 1 || streamKey.equals(deadLetterKey))
            throw new IllegalArgumentException("Invalid stream retention, retry limit or dead-letter key");
        this.redis = redis;
        this.mapper = mapper;
        this.streamKey = streamKey;
        this.group = group;
        this.deadLetterKey = deadLetterKey;
        this.maxDeliveries = maxDeliveries;
        this.retentionSeconds = retentionSeconds;
        this.deadLetterRetentionSeconds = deadLetterRetentionSeconds;
    }

    int maxDeliveries() { return maxDeliveries; }

    void initialize() { redis.execute(INITIALIZE, List.of(deadLetterKey)); }

    void deadLetter(MapRecord<String, String, String> message, String reason, long attempts) {
        try {
            redis.execute(DEAD_LETTER, List.of(streamKey, deadLetterKey), group, message.getId().getValue(),
                    reason, Long.toString(attempts), Long.toString(Instant.now().toEpochMilli()),
                    mapper.writeValueAsString(message.getValue()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize rejected notification", exception);
        }
    }

    void trim() {
        long now = Instant.now().toEpochMilli();
        redis.execute(TRIM, List.of(streamKey), (now - retentionSeconds * 1000) + "-0");
        redis.execute(TRIM, List.of(deadLetterKey), (now - deadLetterRetentionSeconds * 1000) + "-0");
    }
}
