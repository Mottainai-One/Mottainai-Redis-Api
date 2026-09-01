package com.mottainai.redis;

final class NotificationKeys {
    static final String PREFIX = "mottainai:v1:notification:";
    static final long TTL_SECONDS = 604800;
    // Priority is the primary ranking key; recency only breaks ties inside the same priority.
    static final long PRIORITY_WEIGHT = 10_000_000_000L;

    private NotificationKeys() {}

    static String inbox(long companyId, long userId) {
        return PREFIX + "inbox:" + companyId + ":" + userId;
    }

    static String unread(long companyId, long userId) {
        return PREFIX + "unread:" + companyId + ":" + userId;
    }

    static String data(long companyId, long userId, String notificationId) {
        return PREFIX + "data:" + companyId + ":" + userId + ":" + notificationId;
    }

    static long score(int priority, long createdAt) {
        return priority * PRIORITY_WEIGHT + createdAt;
    }
}
