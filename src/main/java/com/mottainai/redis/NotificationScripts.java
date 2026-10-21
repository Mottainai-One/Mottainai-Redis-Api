package com.mottainai.redis;

final class NotificationScripts {
    private NotificationScripts() {}

    // Reconcile the index and count with live hashes in the same Redis operation.
    // Hash TTLs are authoritative; reads never extend a notification's lifetime.
    static final String RECONCILE = """
        local function reconcile(inbox, counter, prefix)
            local active = {}
            local unread = 0
            local ttl = 0
            local persistent = false
            for _, id in ipairs(redis.call('ZREVRANGE', inbox, 0, -1)) do
                local key = prefix .. id
                if redis.call('EXISTS', key) == 0 then
                    redis.call('ZREM', inbox, id)
                else
                    table.insert(active, id)
                    if redis.call('HGET', key, 'status') == 'unread' then unread = unread + 1 end
                    local remaining = redis.call('TTL', key)
                    if remaining == -1 then persistent = true end
                    ttl = math.max(ttl, remaining)
                end
            end
            if #active == 0 then
                redis.call('DEL', inbox, counter)
            elseif persistent then
                redis.call('SET', counter, unread)
                redis.call('PERSIST', inbox)
            else
                redis.call('SET', counter, unread, 'EX', math.max(1, ttl))
                redis.call('EXPIRE', inbox, math.max(1, ttl))
            end
            return active, unread
        end
        """;
}
