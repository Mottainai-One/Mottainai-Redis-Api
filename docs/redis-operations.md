# Redis operations

## Topology

The protected instance owns notification hashes, inboxes, counters, Streams, rate limits,
token revocations and idempotency controls. It uses the existing `mottainai-redis-data`
volume, AOF, a 256 MB limit and `noeviction`. Memory exhaustion rejects writes rather
than evicting control keys. Monitor memory and handle Redis errors; AOF is not a backup.

The separate cache instance uses 128 MB and `allkeys-lru`, without persistence. It has
independent credentials and a separate memory budget. Logical databases in a single
instance do not provide this separation.

## Existing installation migration

1. Confirm the current volume, network, password and clients. The supplied Compose file
   expects `mottainai-redis-data`, `mottainai-redis-network` and the existing protected
   password. Set a distinct cache password in ignored `.env.redis`.
2. Pause producers and take a consistent RDB backup with authenticated `redis-cli --rdb`
   before stopping the old instance. Store a copy outside the live volume. Record Stream
   lengths, consumer groups and pending counts. Do not print credentials or full payloads.
3. Stop the existing Redis container and rename it as a rollback container. Keep its
   volume attached. Never run both protected containers against the volume at once.
4. Run `docker compose --env-file .env.redis -f docker-compose.redis.yml up -d`.
   Verify authentication, `noeviction`, AOF, the existing groups and records before
   starting the notification consumer. Verify `allkeys-lru` on the cache instance.
5. Route only RAG cache reads/writes from the IA client to `REDIS_CACHE_URL` and
   `REDIS_CACHE_PASSWORD`. On Windows the endpoint is `redis://127.0.0.1:6382/0`;
   in containers on the shared network it is `redis://redis-cache:6379/0`.
   Keep all protected operations on `REDIS_URL` and close both pools on shutdown.
   Disable caching when the cache endpoint is absent or unavailable; never fall back
   to the protected instance. Remove expired legacy RAG keys only after verifying the
   client's routing; do not flush either database.
6. Preserve the previous API image for rollback, then run
   `docker compose up -d --build mobile-api`. Verify `/readyz`, notification reads,
   pending-message recovery, dead-letter writes and retention before resuming producers.

## Failure handling and retention

Malformed payloads are transferred immediately. Valid messages that keep failing are
transferred after five deliveries by default. The transfer appends the original payload,
source ID, group, reason, attempt count and timestamp, then acknowledges the source
in one Lua operation. If appending fails, the source stays pending. A replay of an
already acknowledged delivery does not append a duplicate dead letter.

Retention runs every minute, using seven days for the source and 30 days for the
dead-letter Stream by default. The safe trim boundary is the earliest of the age cutoff,
each group's last delivered ID and each group's oldest pending ID. Thus slow groups,
pending entries and undelivered backlog can retain older history indefinitely. Monitor
these conditions instead of forcibly trimming. Configure a review consumer group on
the dead-letter Stream if unresolved failures must be retained beyond 30 days.

## Rollback

Stop the API and producers. Stop and remove only the new protected container, leaving
the external volume intact. Rename the preserved old container to `mottainai-redis`,
then start it. Do not run `docker compose down -v`. Restore the preserved API image
with a Compose image override if needed. Keep the cache instance or disable the IA's
RAG cache; never redirect it to the protected database as an implicit fallback.

A rollback to the old container restores its older eviction policy. Check that risk
before resuming traffic. Stream retention is irreversible without the pre-migration
backup; container rollback alone does not restore records already trimmed.
