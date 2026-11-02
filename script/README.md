# Redis scripts

These `redis-cli` command files seed isolated demo keys for company and user
`2147483647`. They do not contain credentials or real customer data.

Paste `redis_protected.redis` into the Redis instance configured as
`REDIS_URL` by Mottainai-IA. Paste `redis_cache.redis` into the separate
instance configured as `REDIS_CACHE_URL`. On the local Docker setup, those
instances are exposed on ports 6379 and 6382 respectively. Authenticate using
the corresponding local environment credentials.

| Key family | Type | TTL | Writer |
| --- | --- | --- | --- |
| `mottainai:v1:rate-limit:*` | sorted set | 60 seconds | IA rate limiter |
| `mottainai:v1:auth:revoked:*` | string | until token expiry | IA authentication |
| `mottainai:v1:alert-webhook-sent:*` | string | 7 days | IA alert sender |
| `mottainai:v1:idempotency:*` | JSON string | 24 hours | IA business operation |
| `mottainai:v1:idempotency:*:lock` | lock | until release/reconciliation | IA business operation |
| `mottainai:v1:notification:stream` | stream | retention managed by Redis API | IA notification publisher |
| `mottainai:v1:notification:data:*` | hash | 7 days | Redis API stream consumer |
| `mottainai:v1:notification:inbox:*` | sorted set | 7 days | Redis API stream consumer |
| `mottainai:v1:notification:unread:*` | string | 7 days | Redis API stream consumer |
| `mottainai:v1:rag:*` | JSON string | 5 minutes | IA RAG cache |

The stream consumer creates notification hashes, inboxes and counters from the
`XADD` entry. It also creates the consumer group and dead-letter stream at
startup. Do not prefill lock keys or dead-letter messages. The application's
runtime creates and expires production keys; these files are demo inserts.
