# Mottainai — Redis Mobile API

Essa API é a API feita em REDIS para as notificações do aplicativo Mottainai. 

Construída com Java 17 e Spring Boot 3.4.2, ela atua exclusivamente como uma fachada de leitura (read-only facade). O seu principal objetivo é permitir que o aplicativo mobile consuma e gerencie o estado das notificações (como alertas de queima de estoque ou mensagens operacionais) de forma totalmente segura, garantindo que o dispositivo móvel nunca tenha acesso direto ao banco de dados Redis.  

## 🔒 Segurança e Infraestrutura

A arquitetura foi desenhada para evitar qualquer tipo de manipulação ou forja de alertas por parte do cliente:

* **Autenticação Restrita:** Utiliza tokens JWT Bearer HS256 (com secret de no mínimo 32 caracteres).
* **Claims Obrigatórios:** O token deve conter obrigatoriamente `exp`, `sub`, `empresa_id` e `role`.
* **Controle de Perfis:** A API aceita requisições apenas dos perfis `CLIENTE`, `ESTOQUISTA`, `GERENTE` e `DONO`.
* **Isolamento Multi-tenant:** Os dados de identificação (empresa e usuário) são extraídos exclusivamente do token JWT. O cliente não pode forjar acessos passando parâmetros no corpo da requisição.
* **Topologia de Rede:** O banco Redis vive protegido dentro da rede interna do Docker (`mottainai-redis-network`). A API é a única ponte e expõe comunicação apenas localmente (`127.0.0.1:8080`).

## 🗂️ Escopo e Endpoints

**Nota Arquitetural:** Esta API não cria notificações recebendo chamadas HTTP externas. A criação de alertas é de responsabilidade exclusiva do Backend principal do Mottainai ou via scripts internos de teste. Isso garante que o app mobile não consiga inventar falsos avisos operacionais.

Os endpoints expostos são focados estritamente na leitura e controle de estado:

| Endpoint | Ação / Propósito |
|---|---|
| `GET /livez` | Healthcheck básico. Confirma que o processo Java está vivo (não acessa o Redis). |
| `GET /readyz` | Healthcheck de infraestrutura. Confirma que o banco Redis está acessível e respondendo. |
| `GET /v1/notifications?limit=20` | Lista as notificações pertencentes ao usuário autenticado e retorna o total de mensagens não lidas. |
| `POST /v1/notifications/{id}/read` | Marca uma notificação como lida. É uma operação idempotente (pode ser chamada múltiplas vezes sem duplicar ou corromper o estado). |

## 🤖 Ingestão via Redis Stream (serviço de IA)

A criação de notificações continua **fora do HTTP**. O serviço de IA publica em um Redis Stream e a API consome com um *consumer group*, gravando no formato que o app lê. Como o Stream só é alcançável dentro da rede Docker e exige a senha do Redis, o app mobile continua sem conseguir forjar alertas.

**Stream:** `mottainai:v1:notification:stream` · **Consumer group:** `notification-writers`

| Campo | Obrigatório | Regra |
|---|---|---|
| `company_id` | sim | inteiro positivo |
| `user_id` | sim | inteiro positivo |
| `title` | sim | até 120 caracteres |
| `body` | sim | até 1000 caracteres |
| `priority` | sim | `1`, `2` ou `3` |
| `id` | não | sem `:`, até 128 caracteres; se ausente, usa `stream-<ID do registro>` |

Exemplo de publicação:

```bash
XADD mottainai:v1:notification:stream * \
  company_id 1 user_id 301 priority 3 \
  title "Sugestao da IA: promocao no setor 3" \
  body "Modelo identificou 12 itens com risco de perda em 48h."
```

A escrita é atômica (hash, inbox e contador de não lidas em um único script Lua) e idempotente por `id`. Mensagens malformadas são encaminhadas ao Stream de falhas antes da confirmação (`XACK`).

Reenviar um ID ainda existente preserva conteúdo, status de leitura, data e TTL. Sem `id`, o identificador derivado do registro do Stream evita duplicar a notificação ao recuperar a mesma entrega; duas publicações distintas continuam sendo duas notificações. A deduplicação dura enquanto o hash existir (normalmente sete dias).

O consumidor consulta lotes de até 100 mensagens a cada segundo e recupera entregas pendentes com `XPENDING` e `XCLAIM`. O tempo mínimo sem atividade é configurável por `NOTIFICATION_STREAM_RECLAIM_IDLE_MS` (padrão: 60000 ms). Falhas de escrita ou de confirmação mantêm a mensagem pendente para nova tentativa; após `NOTIFICATION_STREAM_MAX_DELIVERIES` entregas (padrão: 5), uma nova falha encaminha a mensagem para análise. O grupo e o Stream são criados na inicialização, inclusive em um Redis vazio; erros diferentes de `BUSYGROUP` interrompem a inicialização.

### Stream de falhas e retenção

`mottainai:v1:notification:dead-letter` guarda `source_stream`, `source_id`, `consumer_group`, `reason`, `attempts`, `failed_at` (milissegundos UTC) e `payload` (JSON original). A transferência e o `XACK` ocorrem no mesmo script Lua, verificando primeiro se a entrega continua pendente. Se a gravação falhar, a mensagem original permanece pendente. Repetir a transferência de uma entrega já confirmada não duplica a falha. A chave é criada vazia na inicialização; não é preciso inserir dados fictícios.

A manutenção roda a cada minuto. O histórico principal tem retenção de sete dias (`NOTIFICATION_STREAM_RETENTION_SECONDS=604800`); a fila de falhas, 30 dias (`NOTIFICATION_DEAD_LETTER_RETENTION_SECONDS=2592000`). O corte usa `XTRIM MINID`, limitado pelo último ID entregue e pelo menor ID pendente de **cada grupo existente**. Mensagens pendentes e ainda não entregues são preservadas, mesmo se ultrapassarem o prazo. Por segurança, o último registro entregue de cada grupo também pode permanecer. Na fila de falhas sem grupos, registros antigos são removidos pelo prazo; crie um grupo de revisão se precisar proteger falhas ainda não analisadas. Grupos abandonados seguram a limpeza e devem ser acompanhados. Um grupo criado depois da limpeza não recupera histórico já removido.

Esses limites não são um teto rígido de memória: acompanhe tamanho, atraso e pendências. A API não remove grupos nem confirma mensagens de outros consumidores.

A listagem, a criação e a marcação como lida reconciliam o índice e o contador com os hashes existentes em Lua. Referências expiradas não ocupam o limite de resultados nem contam como não lidas. O TTL dos hashes não é renovado pela leitura. A reconciliação percorre a caixa inteira do usuário, portanto seu custo cresce com o número de notificações ainda indexadas.

## Testes

Execute `mvn verify` para os testes unitários. Para executar também os testes de integração, disponibilize um Redis exclusivo para testes e configure `REDIS_TEST_HOST` e, se necessário, `REDIS_TEST_PORT` (padrão: 6379). Os testes usam chaves próprias e não exigem o `.env` da aplicação. O GitHub Actions executa as duas suítes com Redis 7.2.5.

## ⚙️ Configuração do Redis

`docker-compose.redis.yml` provisiona duas instâncias na rede externa `mottainai-redis-network`:

- `mottainai-redis` (`redis:6379`, host `127.0.0.1:6379`): notificações, Streams, limites, revogações e idempotência; AOF com sincronização a cada segundo, volume externo existente `mottainai-redis-data`, limite de 256 MB e política `noeviction`. Ao atingir o limite, novas gravações podem falhar; nenhuma chave é removida para abrir espaço.
- `mottainai-redis-cache` (`redis-cache:6379`, host `127.0.0.1:6382`): somente cache descartável; 128 MB, `allkeys-lru`, sem AOF ou snapshots. Usa uma senha própria. Apagar esse cache não deve afetar notificações ou controles.

Copie `.env.redis.example` para `.env.redis`, configure as senhas e execute `docker compose --env-file .env.redis -f docker-compose.redis.yml up -d`. Em uma instalação nova, crie a rede e o volume externo antes. Em uma instalação existente, siga [a migração e o rollback](docs/redis-operations.md); não substitua o volume. A API continua usando `REDIS_URL` para a instância protegida. O cliente da IA deve enviar apenas o cache RAG por `REDIS_CACHE_URL` e `REDIS_CACHE_PASSWORD`, sem fallback para a instância protegida.
