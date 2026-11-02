# API Redis de notificações


A API Java/Spring lê notificações de um usuário e permite marcá-las como lidas. Produtores autorizados publicam no Stream Redis ou usam a rota HTTP interna protegida por segredo; o aplicativo consome as notificações pelas rotas de leitura. O contrato do Stream, as chaves e a retenção estão no [guia do banco Redis](https://github.com/Mottainai-One/Mottainai-Hub/blob/main/Docs/banco/redis/01-estruturas-e-notificacoes.md).

## Autenticação e configuração

As rotas de notificações exigem `Authorization: Bearer <token>`. A API valida a assinatura JWT e a expiração; o código exige `exp`, `sub`, `empresa_id` e `role`. `empresa_id` e o identificador do usuário devem representar inteiros positivos. O usuário vem de `usuario_id`, quando presente, ou de `sub`; tokens com `usuario_id` exigem `use=access`. Os perfis aceitos são `CLIENTE`, `ESTOQUISTA`, `GERENTE` e `DONO`. A empresa e o usuário são extraídos do token, não de parâmetros do cliente. Token ausente, inválido, expirado ou com claims inválidos resulta em `401`.

A configuração usa `REDIS_URL`, `JWT_SECRET` e `JWT_ALGORITHM`, cujo padrão é `HS256`; apenas esse algoritmo é aceito e `JWT_SECRET` deve estar em Base64 e conter pelo menos 32 bytes após a decodificação. O processo escuta na porta `8080`; a exposição de rede depende do ambiente. Os endpoints de saúde não exigem o cabeçalho JWT neste controller. Nunca inclua tokens reais ou senhas em exemplos, logs ou chamados.

## Saúde

| Método e rota | Sucesso | Significado |
|---|---|---|
| `GET /livez` | `200`, `{"status":"alive"}` | Processo vivo; não consulta o Redis. |
| `GET /readyz` | `200`, `{"status":"ready"}` | Redis respondeu ao ping. Falha de conexão retorna `503`. |

`/readyz` verifica conectividade, não comprova que uma notificação foi produzida, processada e entregue ao aplicativo.

## Listar notificações

```http
GET /v1/notifications?limit=20
Authorization: Bearer <token>
```

`limit` é opcional, padrão `20`, e deve estar entre 1 e 100; fora desse intervalo retorna `400`. A resposta contém o contador total de não lidas e até `limit` notificações disponíveis, ordenadas por prioridade e, em caso de empate, pela data de criação:

```json
{
  "unreadCount": 1,
  "notifications": [
    {
      "id": "alerta-123",
      "title": "Produto próximo da validade",
      "body": "Confira os itens do setor.",
      "priority": 3,
      "status": "unread",
      "createdAt": 1793400000
    }
  ]
}
```

`priority` é 1, 2 ou 3; `status` é `unread` ou `read`; `createdAt` é um timestamp Unix em segundos. Para notificações de promoção, a resposta também pode incluir `promotionId`, `storeId`, `productId` e `expiresAt`; consulte o contrato de promoções para as unidades e regras. O contador é reconciliado com os hashes ainda disponíveis e pode ser maior que a quantidade retornada quando o limite restringe a página. A lista não usa paginação por cursor ou offset.

## Marcar como lida

Rota: `POST /v1/notifications/{id}/read`.

```http
POST /v1/notifications/alerta-123/read
Authorization: Bearer <token>
```

Não há corpo de requisição. Em caso de sucesso, inclusive se já estiver lida, retorna `200`:

```json
{"id": "alerta-123", "status": "read"}
```

O identificador não pode estar vazio, conter `:` ou exceder 128 caracteres; entradas inválidas retornam `422`. Uma notificação inexistente ou fora da caixa do usuário retorna `404`. A marcação é idempotente e a leitura não renova o TTL da notificação.

## Publicação interna

`POST /internal/v1/notifications` é destinado a produtores autorizados, não ao aplicativo móvel. Envie `X-Mottainai-Notification-Secret` com o segredo configurado em `NOTIFICATION_PUBLISHER_SECRET` (pelo menos 32 caracteres). O corpo recebe `companyId`, `userId`, `id`, `title`, `body` e `priority`; os campos opcionais de promoção `promotionId`, `storeId`, `productId` e `expiresAt` seguem o [contrato de promoções](docs/promotion-notifications.md). Uma publicação aceita retorna `202` com `id` e `status: queued`. O segredo inválido retorna `401`, o corpo inválido `400` e uma configuração ou fila indisponível `503`.

Quando necessário fora da rede interna, exponha somente a rota interna por proxy HTTPS com controle de acesso. Não exponha a porta Redis.

## Falhas, fluxo e testes

Além de `400`, `401`, `404` e `422` descritos acima, a indisponibilidade detectada pelo endpoint de prontidão retorna `503`. O formato do corpo de erro depende do tratamento padrão do Spring e da configuração do ambiente; não há um contrato de erro customizado demonstrado no controller.

O consumidor confirma mensagens válidas após gravá-las. Mensagens malformadas são encaminhadas ao Stream de falhas antes da confirmação; falhas de gravação ou confirmação permanecem pendentes para nova tentativa, respeitando o limite de entregas configurado. O endpoint de listagem não cria notificações e não aceita uma empresa ou usuário arbitrário no corpo.

Para desenvolvimento, `mvn verify` executa os testes configurados; os testes de integração usam um Redis dedicado indicado por `REDIS_TEST_HOST` e, opcionalmente, `REDIS_TEST_PORT`. Não use uma instância de produção para testes. Consulte o [guia de segurança e operação](https://github.com/Mottainai-One/Mottainai-Hub/blob/main/Docs/banco/redis/02-api-seguranca-e-operacao.md) para retenção, persistência e limites do ambiente.

## Executar

Requisitos: Java 17, Maven e Redis acessível. Configure `REDIS_URL` e `JWT_SECRET` no ambiente e execute:

```bash
mvn verify
mvn spring-boot:run
```

Use `REDIS_TEST_HOST` para habilitar os testes de integração contra um Redis dedicado.

## Stream de falhas e retenção

`mottainai:v1:notification:dead-letter` guarda `source_stream`, `source_id`, `consumer_group`, `reason`, `attempts`, `failed_at` (milissegundos UTC) e `payload` (JSON original). A transferência e o `XACK` ocorrem no mesmo script Lua, verificando primeiro se a entrega continua pendente. Se a gravação falhar, a mensagem original permanece pendente. Repetir a transferência de uma entrega já confirmada não duplica a falha. A chave é criada vazia na inicialização; não é preciso inserir dados fictícios.

A manutenção roda a cada minuto. O histórico principal tem retenção de sete dias (`NOTIFICATION_STREAM_RETENTION_SECONDS=604800`); a fila de falhas, 30 dias (`NOTIFICATION_DEAD_LETTER_RETENTION_SECONDS=2592000`). O corte usa `XTRIM MINID`, limitado pelo último ID entregue e pelo menor ID pendente de **cada grupo existente**. Mensagens pendentes e ainda não entregues são preservadas, mesmo se ultrapassarem o prazo. Por segurança, o último registro entregue de cada grupo também pode permanecer. Na fila de falhas sem grupos, registros antigos são removidos pelo prazo; crie um grupo de revisão se precisar proteger falhas ainda não analisadas. Grupos abandonados seguram a limpeza e devem ser acompanhados. Um grupo criado depois da limpeza não recupera histórico já removido.

Esses limites não são um teto rígido de memória: acompanhe tamanho, atraso e pendências. A API não remove grupos nem confirma mensagens de outros consumidores.

## ⚙️ Configuração do Redis

`docker-compose.redis.yml` provisiona duas instâncias na rede externa `mottainai-redis-network`:

- `mottainai-redis` (`redis:6379`, host `127.0.0.1:6379`): notificações, Streams, limites, revogações e idempotência; AOF com sincronização a cada segundo, volume externo existente `mottainai-redis-data`, limite de 256 MB e política `noeviction`. Ao atingir o limite, novas gravações podem falhar; nenhuma chave é removida para abrir espaço.
- `mottainai-redis-cache` (`redis-cache:6379`, host `127.0.0.1:6382`): somente cache descartável; 128 MB, `allkeys-lru`, sem AOF ou snapshots. Usa uma senha própria. Apagar esse cache não deve afetar notificações ou controles.

Copie `.env.redis.example` para `.env.redis`, configure as senhas e execute `docker compose --env-file .env.redis -f docker-compose.redis.yml up -d`. Em uma instalação nova, crie a rede e o volume externo antes. Em uma instalação existente, siga [a migração e o rollback](docs/redis-operations.md); não substitua o volume. A API continua usando `REDIS_URL` para a instância protegida. O cliente da IA deve enviar apenas o cache RAG por `REDIS_CACHE_URL` e `REDIS_CACHE_PASSWORD`, sem fallback para a instância protegida.

Para destino de produto, unidades de data, vencimento e compatibilidade, consulte [avisos de promoções](docs/promotion-notifications.md).
