# API Redis de notificações


A API Java/Spring lê notificações de um usuário e permite marcá-las como lidas. A criação não é exposta por HTTP: produtores autorizados escrevem no Stream Redis, que é consumido pela API. O contrato do Stream, as chaves e a retenção estão no [guia do banco Redis](https://github.com/Mottainai-One/Mottainai-Hub/blob/main/Docs/banco/redis/01-estruturas-e-notificacoes.md).

## Autenticação e configuração

As rotas de notificações exigem `Authorization: Bearer <token>`. A API valida a assinatura JWT e a expiração; o código exige `exp`, `sub`, `empresa_id` e `role`. `sub` e `empresa_id` devem representar inteiros positivos. Os perfis aceitos são `CLIENTE`, `ESTOQUISTA`, `GERENTE` e `DONO`. A empresa e o usuário são extraídos do token, não de parâmetros do cliente. Token ausente, inválido, expirado ou com claims inválidos resulta em `401`.

A configuração usa `REDIS_URL`, `JWT_SECRET` e `JWT_ALGORITHM`, cujo padrão é `HS256`; apenas esse algoritmo é aceito e o segredo deve ter pelo menos 32 caracteres. O processo escuta na porta `8080`; a exposição de rede depende do ambiente. Os endpoints de saúde não exigem o cabeçalho JWT neste controller. Nunca inclua tokens reais ou senhas em exemplos, logs ou chamados.

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

`priority` é 1, 2 ou 3; `status` é `unread` ou `read`; `createdAt` é um timestamp Unix em segundos. O contador é reconciliado com os hashes ainda disponíveis e pode ser maior que a quantidade retornada quando o limite restringe a página. A lista não usa paginação por cursor ou offset.

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

## Falhas, fluxo e testes

Além de `400`, `401`, `404` e `422` descritos acima, a indisponibilidade detectada pelo endpoint de prontidão retorna `503`. O formato do corpo de erro depende do tratamento padrão do Spring e da configuração do ambiente; não há um contrato de erro customizado demonstrado no controller.

O consumidor do Stream confirma mensagens válidas após gravá-las. Mensagens malformadas são registradas e confirmadas sem criar notificação; falhas de gravação ou confirmação podem permanecer pendentes para nova tentativa. O endpoint de listagem não cria notificações e não aceita uma empresa ou usuário arbitrário no corpo.

Para desenvolvimento, `mvn verify` executa os testes configurados; os testes de integração usam um Redis dedicado indicado por `REDIS_TEST_HOST` e, opcionalmente, `REDIS_TEST_PORT`. Não use uma instância de produção para testes. Consulte o [guia de segurança e operação](https://github.com/Mottainai-One/Mottainai-Hub/blob/main/Docs/banco/redis/02-api-seguranca-e-operacao.md) para retenção, persistência e limites do ambiente.

## Executar

Requisitos: Java 17, Maven e Redis acessível. Configure `REDIS_URL` e `JWT_SECRET` no ambiente e execute:

```bash
mvn verify
mvn spring-boot:run
```

Use `REDIS_TEST_HOST` para habilitar os testes de integração contra um Redis dedicado.
