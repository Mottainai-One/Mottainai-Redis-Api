# Mottainai Redis Mobile API — Java

API **Spring Boot 3.4.2 / Java 17**. É uma fachada de leitura para o app mobile consumir notificações com segurança; o mobile não acessa Redis diretamente.

## Escopo da API

| Endpoint | Escopo |
|---|---|
| `GET /livez` | Processo Java está vivo; não acessa Redis. |
| `GET /readyz` | Redis está acessível. |
| `GET /v1/notifications?limit=20` | Lista as notificações do usuário autenticado e o total não lido. |
| `POST /v1/notifications/{id}/read` | Marca como lida; pode repetir a chamada sem duplicar efeito. |

Ela **não cria notificações por HTTP**. A criação é feita pelo backend Mottainai ou pelo script de teste `mottainai-redis-cli/inserir-notificacao.ps1`. Isso evita que um app mobile consiga forjar alertas operacionais.

## Segurança

- JWT Bearer HS256 com segredo mínimo de 32 caracteres;
- exige `exp`, `sub`, `empresa_id` e `role`;
- aceita apenas `CLIENTE`, `ESTOQUISTA`, `GERENTE` e `DONO`;
- empresa e usuário vêm exclusivamente do token;
- Redis fica na rede Docker interna `mottainai-redis-network`;
- a API expõe a porta apenas em `127.0.0.1:8080`.

## Subir no Windows

Primeiro suba o Redis único:

```powershell
cd "$HOME\Documents\mottainai-redis"
.\start-redis.ps1
```

Depois configure e suba a API Java:

```powershell
cd "$HOME\Documents\mottainai-redis-mobile-api"
Copy-Item .env.example .env
notepad .env
# REDIS_URL: use a mesma senha de mottainai-redis.
# JWT_SECRET: use o mesmo segredo da API principal.
docker compose up --build -d
```

Verifique:

```powershell
curl http://127.0.0.1:8080/livez
curl http://127.0.0.1:8080/readyz
```

Logs e parada:

```powershell
docker compose logs -f mobile-api
docker compose down
```

Não versione `.env`.
