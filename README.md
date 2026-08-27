# Mottainai Redis Mobile API — Java

API **Spring Boot 3.4.2 / Java 17**. It is a read-only facade for the mobile app to securely consume notifications; the mobile app never accesses Redis directly.

## API scope

| Endpoint | Scope |
|---|---|
| `GET /livez` | The Java process is alive; does not access Redis. |
| `GET /readyz` | Redis is reachable. |
| `GET /v1/notifications?limit=20` | Lists the authenticated user's notifications and the unread total. |
| `POST /v1/notifications/{id}/read` | Marks as read; can be called again without duplicating the effect. |

It **does not create notifications over HTTP**. Creation is done by the Mottainai backend or by the test script `mottainai-redis-cli/inserir-notificacao.ps1`. This prevents a mobile app from being able to forge operational alerts.

## Security

- JWT Bearer HS256 with a secret of at least 32 characters;
- requires `exp`, `sub`, `empresa_id` and `role`;
- only accepts `CLIENTE`, `ESTOQUISTA`, `GERENTE` and `DONO`;
- company and user come exclusively from the token;
- Redis lives on the internal Docker network `mottainai-redis-network`;
- the API only exposes the port on `127.0.0.1:8080`.

## Running on Windows

First bring up the single Redis instance:

```powershell
cd "$HOME\Documents\mottainai-redis"
.\start-redis.ps1
```

Then configure and start the Java API:

```powershell
cd "$HOME\Documents\mottainai-redis-mobile-api"
Copy-Item .env.example .env
notepad .env
# REDIS_URL: use the same password as mottainai-redis.
# JWT_SECRET: use the same secret as the main API.
docker compose up --build -d
```

Check it:

```powershell
curl http://127.0.0.1:8080/livez
curl http://127.0.0.1:8080/readyz
```

Logs and shutdown:

```powershell
docker compose logs -f mobile-api
docker compose down
```

Do not commit `.env`.
