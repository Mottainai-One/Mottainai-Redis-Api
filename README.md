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
| `id` | não | sem `:`, até 128 caracteres; gerado automaticamente se ausente |

Exemplo de publicação:

```bash
XADD mottainai:v1:notification:stream * \
  company_id 1 user_id 301 priority 3 \
  title "Sugestao da IA: promocao no setor 3" \
  body "Modelo identificou 12 itens com risco de perda em 48h."
```

A escrita é atômica (hash, inbox e contador de não lidas em um único script Lua) e idempotente por `id`. Mensagens malformadas são descartadas com log e confirmadas (`XACK`), para não ficarem em redelivery infinito nem corromper o caminho de leitura.

## ⚙️ Configuração do Redis

O container roda com `--appendonly yes` (persistência), `--maxmemory 256mb` e `--maxmemory-policy volatile-lru`. O limite evita que o cache do serviço de IA cresça sem teto e derrube o container por falta de memória; como todas as chaves têm TTL, a política despeja as menos usadas antes de chegar nesse ponto.
